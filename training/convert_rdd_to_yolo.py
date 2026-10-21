#!/usr/bin/env python3
"""Convert RDD2022 (Pascal VOC XML) -> YOLO detection format for RoadDefectAI.

The Android app expects EXACTLY four classes in this index order (see
app/src/main/java/com/roaddefect/ai/ai/DefectType.kt):

    0 pothole             (RDD label D40)
    1 longitudinal_crack  (RDD label D00)
    2 transverse_crack    (RDD label D10)
    3 alligator_crack     (RDD label D20)

RDD2022's "Repair" class is NOT one of the app's four defects and is dropped
(configurable with --keep-repair, off by default).

Outputs a ready-to-train Ultralytics dataset:

    <out>/images/{train,val}/*.jpg
    <out>/labels/{train,val}/*.txt
    <out>/data.yaml
"""

from __future__ import annotations

import argparse
import random
import shutil
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

# RDD2022 raw label -> app class index (order matters, must match DefectType.kt)
CLASS_MAP = {
    "D40": 0,  # pothole
    "D00": 1,  # longitudinal_crack
    "D10": 2,  # transverse_crack
    "D20": 3,  # alligator_crack
}
CLASS_NAMES = ["pothole", "longitudinal_crack", "transverse_crack", "alligator_crack"]
REPAIR_LABEL = "Repair"


def resolve_split_root(path: Path) -> Path:
    """Accept either the RDD country folder or the nested one that holds train/test."""
    candidates = [path, path / path.name]
    for cand in candidates:
        if (cand / "train" / "annotations" / "xmls").is_dir():
            return cand
    raise SystemExit(
        f"Could not find 'train/annotations/xmls' under {path}. "
        "Point --rdd at the China_MotorBike folder (either nesting level works)."
    )


def parse_xml(xml_path: Path, keep_repair: bool):
    """Return (width, height, [(class_index, xmin, ymin, xmax, ymax), ...], counters)."""
    root = ET.parse(xml_path).getroot()
    size = root.find("size")
    width = int(float(size.findtext("width")))
    height = int(float(size.findtext("height")))

    boxes = []
    stats = Counter()
    for obj in root.findall("object"):
        name = (obj.findtext("name") or "").strip()
        if name == REPAIR_LABEL:
            stats["repair_dropped"] += 1
            continue
        if name not in CLASS_MAP:
            stats[f"unknown:{name}"] += 1
            continue
        bnd = obj.find("bndbox")
        xmin = float(bnd.findtext("xmin"))
        ymin = float(bnd.findtext("ymin"))
        xmax = float(bnd.findtext("xmax"))
        ymax = float(bnd.findtext("ymax"))

        # Clamp to image bounds, then drop degenerate boxes.
        xmin = max(0.0, min(xmin, width))
        xmax = max(0.0, min(xmax, width))
        ymin = max(0.0, min(ymin, height))
        ymax = max(0.0, min(ymax, height))
        if xmax - xmin < 1.0 or ymax - ymin < 1.0:
            stats["degenerate_dropped"] += 1
            continue
        boxes.append((CLASS_MAP[name], xmin, ymin, xmax, ymax))
        stats[f"kept:{CLASS_MAP[name]}"] += 1
    return width, height, boxes, stats


def to_yolo_line(box, width, height) -> str:
    cls, xmin, ymin, xmax, ymax = box
    cx = (xmin + xmax) / 2.0 / width
    cy = (ymin + ymax) / 2.0 / height
    w = (xmax - xmin) / width
    h = (ymax - ymin) / height
    return f"{cls} {cx:.6f} {cy:.6f} {w:.6f} {h:.6f}"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--rdd", required=True, type=Path, help="RDD2022 country folder (China_MotorBike)")
    ap.add_argument("--out", required=True, type=Path, help="Output dataset directory")
    ap.add_argument("--val-fraction", type=float, default=0.15, help="Fraction held out for validation (default 0.15)")
    ap.add_argument("--seed", type=int, default=42, help="Split seed (default 42)")
    ap.add_argument("--keep-repair", action="store_true", help="Keep Repair as an extra class (index 4) - NOT app compatible")
    ap.add_argument("--copy", action="store_true", help="Copy images instead of hard-linking")
    ap.add_argument("--dry-run", action="store_true", help="Parse and report only; write nothing")
    args = ap.parse_args()

    root = resolve_split_root(args.rdd)
    xml_dir = root / "train" / "annotations" / "xmls"
    img_dir = root / "train" / "images"
    xmls = sorted(xml_dir.glob("*.xml"))
    if not xmls:
        raise SystemExit(f"No XML files found in {xml_dir}")

    names = list(CLASS_NAMES)
    if args.keep_repair:
        names.append("repair")

    records = []  # (xml_path, width, height, [boxes])
    totals = Counter()
    skipped_empty = 0
    for xml_path in xmls:
        width, height, boxes, stats = parse_xml(xml_path, args.keep_repair)
        totals.update(stats)
        if not boxes:
            skipped_empty += 1
            continue
        records.append((xml_path, width, height, boxes))
        totals["images_kept"] += 1

    print(f"RDD root        : {root}")
    print(f"XMLs found      : {len(xmls)}")
    print(f"Images with >=1 box: {len(records)}")
    print(f"Images skipped (only Repair/none): {skipped_empty}")
    print("Box counts:", {k: v for k, v in sorted(totals.items()) if k.startswith("kept:")})
    if totals.get("repair_dropped"):
        print(f"Repair boxes dropped: {totals['repair_dropped']}")
    if any(k.startswith("unknown:") for k in totals):
        print("WARNING unknown labels:", {k: v for k, v in totals.items() if k.startswith("unknown:")})

    if args.dry_run:
        print("Dry run - nothing written.")
        return 0

    # Deterministic split.
    rng = random.Random(args.seed)
    rng.shuffle(records)
    n_val = max(1, round(len(records) * args.val_fraction))
    val_set = records[:n_val]
    train_set = records[n_val:]

    out = args.out
    if out.exists():
        shutil.rmtree(out)
    for split in ("train", "val"):
        (out / "images" / split).mkdir(parents=True, exist_ok=True)
        (out / "labels" / split).mkdir(parents=True, exist_ok=True)

    link_or_copy = shutil.copy2 if args.copy else (lambda s, d: _link_or_copy(s, d))

    written = Counter()
    for split, items in (("train", train_set), ("val", val_set)):
        for xml_path, width, height, boxes in items:
            stem = xml_path.stem
            src_img = _find_image(img_dir, stem)
            if src_img is None:
                print(f"WARNING no image for {stem}, skipping")
                continue
            link_or_copy(src_img, out / "images" / split / src_img.name)
            lines = [to_yolo_line(b, width, height) for b in boxes]
            (out / "labels" / split / f"{stem}.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
            written[split] += 1

    names_block = "\n".join(f"  {i}: {n}" for i, n in enumerate(names))
    data_yaml = (
        f"path: {out.resolve().as_posix()}\n"
        f"train: images/train\n"
        f"val: images/val\n"
        f"nc: {len(names)}\n"
        f"names:\n{names_block}\n"
    )
    (out / "data.yaml").write_text(data_yaml, encoding="utf-8")

    print(f"\nWrote dataset -> {out.resolve()}")
    print(f"  train images: {written['train']}")
    print(f"  val images  : {written['val']}")
    print(f"  data.yaml   :\n{data_yaml}")
    return 0


def _link_or_copy(src: Path, dst: Path) -> None:
    """Hard-link when possible (same volume), else copy."""
    try:
        if dst.exists():
            dst.unlink()
        import os

        os.link(src, dst)
    except OSError:
        shutil.copy2(src, dst)


def _find_image(img_dir: Path, stem: str):
    for ext in (".jpg", ".jpeg", ".png", ".JPG"):
        cand = img_dir / f"{stem}{ext}"
        if cand.is_file():
            return cand
    return None


if __name__ == "__main__":
    sys.exit(main())
