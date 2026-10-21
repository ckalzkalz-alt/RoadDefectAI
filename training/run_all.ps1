# RoadDefectAI - run the whole training pipeline end to end.
#   powershell -ExecutionPolicy Bypass -File training\run_all.ps1
# Assumes install.ps1 has already been run (training\.venv exists).

param(
    [string]$Rdd = "..\RDD2022_released_through_CRDDC2022\RDD2022\China_MotorBike",
    [string]$Model = "yolov8n.pt",
    [int]$Epochs = 100,
    [int]$Imgsz = 640,
    [int]$Batch = 16,
    [string]$Device = "0"
)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$py = Join-Path $here ".venv\Scripts\python.exe"

if (-not (Test-Path $py)) { throw "venv missing. Run training\install.ps1 first." }

Push-Location $here
try {
    Write-Host "=== 1/4  Convert RDD -> YOLO ===" -ForegroundColor Cyan
    & $py convert_rdd_to_yolo.py --rdd $Rdd --out dataset

    Write-Host "=== 2/4  Train ===" -ForegroundColor Cyan
    & $py train.py --model $Model --epochs $Epochs --imgsz $Imgsz --batch $Batch --device $Device --cache

    Write-Host "=== 3/4  Export TFLite into app assets ===" -ForegroundColor Cyan
    & $py export_tflite.py --imgsz $Imgsz

    Write-Host "=== 4/4  Evaluate the exported model on the val split ===" -ForegroundColor Cyan
    & $py eval_tflite.py --nms-iou 0.5
}
finally {
    Pop-Location
}

Write-Host "Pipeline complete." -ForegroundColor Green
Write-Host "Now bump DetectorFactory.MODEL_VERSION and rebuild the app." -ForegroundColor Green
