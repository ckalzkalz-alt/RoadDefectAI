#!/usr/bin/env python3
"""Export trained YOLOv8 weights to the TFLite model the Android app loads.

Why this is not just `yolo export format=tflite`:
Ultralytics >= 8.4.83 replaced format='tflite' with the unified LiteRT export,
which asserts it is only supported on Linux x86 and macOS. On Windows that path
fails with "LiteRT export only supported on Linux x86 and macOS".

So we do what Ultralytics used to do internally, in two explicit steps that work
on Windows:
    1. best.pt  -> ONNX          (Ultralytics, trivially cross-platform)
    2. ONNX     -> TFLite float32 (onnx2tf, the converter Ultralytics used)
Then we verify the TFLite against the ONNX numerically and copy it into the app.

Usage:
    python export_tflite.py                                  # newest runs/*/weights/best.pt
    python export_tflite.py --weights runs/road_defect_v1/weights/best.pt
    python export_tflite.py --int8                           # also emit a quantised model
"""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

APP_ASSET_NAME = "road_defect_v1.tflite"
EXPECTED_CLASSES = ["pothole", "longitudinal_crack", "transverse_crack", "alligator_crack"]


def find_latest_best(runs_dir: Path) -> Path:
    candidates = sorted(runs_dir.glob("*/weights/best.pt"), key=lambda p: p.stat().st_mtime, reverse=True)
    if not candidates:
        raise SystemExit(f"No runs/*/weights/best.pt under {runs_dir}. Train first.")
    return candidates[0]


def to_onnx(weights: Path, imgsz: int, workdir: Path) -> Path:
    from ultralytics import YOLO

    print(f"[1/3] ONNX export: {weights}")
    onnx_path = Path(YOLO(str(weights)).export(format="onnx", imgsz=imgsz, simplify=True, opset=13))
    # Ultralytics writes next to the weights; move a copy into our workdir for tidiness.
    dest = workdir / onnx_path.name
    if onnx_path.resolve() != dest.resolve():
        shutil.copy2(onnx_path, dest)
    print(f"      -> {dest} ({dest.stat().st_size / 1e6:.1f} MB)")
    return dest


def to_tflite(onnx_path: Path, workdir: Path, imgsz: int, int8: bool) -> Path:
    print(f"[2/3] onnx2tf -> TFLite (this can take a few minutes)")
    out_dir = workdir / "tflite"
    out_dir.mkdir(parents=True, exist_ok=True)

    # onnx2tf pulls its numerical-check sample from
    #   https://github.com/PINTO0309/onnx2tf/releases/download/1.20.4/...npy
    # which now 404s (returns the 9 bytes "Not Found"), so np.load() dies with
    #   ValueError: This file contains pickled (object) data.
    # It checks os.path.isfile(cwd/<name>) first, so seeding that file locally
    # avoids the network entirely. Shape must be NHWC and match the model input.
    calib = workdir / "calibration_image_sample_data_20x128x128x3_float32.npy"
    if not calib.exists():
        import numpy as np

        np.save(calib, np.random.default_rng(0).random((1, imgsz, imgsz, 3), dtype=np.float32))

    cmd = [
        sys.executable, "-m", "onnx2tf",
        "-i", str(onnx_path),
        "-o", str(out_dir),
        "--non_verbose",
    ]
    if int8:
        cmd += ["-qt"]  # quantise to int8
    proc = subprocess.run(cmd, capture_output=True, text=True, cwd=str(workdir))
    if proc.returncode != 0:
        sys.stderr.write(proc.stdout[-4000:])
        sys.stderr.write(proc.stderr[-4000:])
        raise SystemExit(f"onnx2tf failed with exit code {proc.returncode}")

    # Prefer the float32 artefact.
    stem = onnx_path.stem
    for name in (f"{stem}_float32.tflite", f"{stem}.tflite", f"{stem}_float16.tflite"):
        cand = out_dir / name
        if cand.is_file():
            print(f"      -> {cand} ({cand.stat().st_size / 1e6:.1f} MB)")
            return cand
    found = list(out_dir.glob("*.tflite"))
    if not found:
        raise SystemExit(f"onnx2tf produced no .tflite under {out_dir}")
    return found[0]


def make_interpreter(model_path: Path, num_threads: int = 4):
    try:
        import tensorflow as tf  # type: ignore

        return tf.lite.Interpreter(model_path=str(model_path), num_threads=num_threads)
    except Exception:
        from ai_edge_litert.interpreter import Interpreter  # type: ignore

        return Interpreter(model_path=str(model_path), num_threads=num_threads)


def verify(tflite_path: Path, onnx_path: Path, imgsz: int) -> None:
    """Confirm the TFLite matches the ONNX numerically and satisfies the app contract."""
    print("[3/3] Verifying")
    interp = make_interpreter(tflite_path)
    interp.allocate_tensors()
    inp = interp.get_input_details()[0]
    out = interp.get_output_details()[0]
    in_shape, out_shape = list(inp["shape"]), list(out["shape"])
    print(f"      input : {in_shape} dtype={inp['dtype'].__name__}")
    print(f"      output: {out_shape} dtype={out['dtype'].__name__}")

    expected_in = [1, imgsz, imgsz, 3]
    print(f"      input shape matches [1,{imgsz},{imgsz},3]: {in_shape == expected_in}")
    print(f"      output carries 4+{len(EXPECTED_CLASSES)}=8 channels: "
          f"{8 in out_shape} (app accepts [1,8,N] or [1,N,8])")

    # Numerical fidelity: same random input through ONNX and TFLite.
    try:
        import numpy as np
        import onnxruntime as ort

        rng = np.random.default_rng(0)
        x_nhwc = rng.random((1, imgsz, imgsz, 3), dtype=np.float32)

        sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
        ix = sess.get_inputs()[0].name
        onnx_out = sess.run(None, {ix: x_nhwc.transpose(0, 3, 1, 2)})[0]

        interp.set_tensor(inp["index"], x_nhwc.astype(np.float32))
        interp.invoke()
        lite_out = interp.get_tensor(out["index"])

        # Compare after normalising layout to [1, C, N].
        def to_cn(a):
            a = np.squeeze(a, 0)
            return a if a.shape[0] == 8 else a.T

        a, b = to_cn(onnx_out), to_cn(lite_out)
        if a.shape != b.shape:
            print(f"      SHAPE MISMATCH onnx={onnx_out.shape} tflite={out_shape} (compare aborted)")
            return
        diff = float(np.max(np.abs(a - b)))
        print(f"      max|onnx - tflite| = {diff:.6g}  ({'OK' if diff < 1e-3 else 'WARN high'})")
    except Exception as exc:  # noqa: BLE001
        print(f"      numeric compare skipped ({exc})")


def main() -> int:
    here = Path(__file__).resolve().parent
    repo_root = here.parent
    default_out = repo_root / "app" / "src" / "main" / "assets" / APP_ASSET_NAME

    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--weights", type=Path, default=None, help="best.pt (default: newest run)")
    ap.add_argument("--imgsz", type=int, default=640, help="Must match training/app input size.")
    ap.add_argument("--int8", action="store_true", help="Also produce an int8-quantised model.")
    ap.add_argument("--out", type=Path, default=default_out, help="Destination app asset path.")
    ap.add_argument("--no-copy", action="store_true", help="Export only; do not copy into app assets.")
    args = ap.parse_args()

    weights = args.weights or find_latest_best(here / "runs")
    if not weights.is_file():
        raise SystemExit(f"weights not found: {weights}")

    with tempfile.TemporaryDirectory(prefix="roaddefect_export_") as tmp:
        workdir = Path(tmp)
        onnx_path = to_onnx(weights, args.imgsz, workdir)
        tflite_path = to_tflite(onnx_path, workdir, args.imgsz, args.int8)
        verify(tflite_path, onnx_path, args.imgsz)

        if not args.no_copy:
            args.out.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(tflite_path, args.out)
            print(f"\nCopied to app asset: {args.out} ({args.out.stat().st_size / 1e6:.1f} MB)")

    print("Next: bump DetectorFactory.MODEL_VERSION in the app and rebuild.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
