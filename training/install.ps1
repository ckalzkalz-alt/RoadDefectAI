# RoadDefectAI - one-shot training environment setup (Windows / PowerShell).
# Creates training/.venv, installs CUDA PyTorch, Ultralytics, and the TFLite export deps.
#
#   powershell -ExecutionPolicy Bypass -File training\install.ps1
#
# Requires Python 3.10-3.12 already installed. Pass -PythonExe to point at it.

param(
    [string]$PythonExe = "",
    [switch]$CpuOnly
)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$venv = Join-Path $here ".venv"

function Resolve-Python {
    if ($PythonExe) { return $PythonExe }
    $candidates = @(
        "$env:LOCALAPPDATA\Programs\Python\Python311\python.exe",
        "$env:LOCALAPPDATA\Programs\Python\Python312\python.exe",
        "$env:LOCALAPPDATA\Programs\Python\Python310\python.exe"
    )
    foreach ($c in $candidates) { if (Test-Path $c) { return $c } }
    $cmd = Get-Command python -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw "Python not found. Install Python 3.10-3.12 or pass -PythonExe <path>."
}

$py = Resolve-Python
Write-Host "Using Python: $py"

if (-not (Test-Path $venv)) {
    Write-Host "Creating venv at $venv"
    & $py -m venv $venv
}
$venvPy = Join-Path $venv "Scripts\python.exe"

Write-Host "Upgrading pip..."
& $venvPy -m pip install --upgrade pip

if ($CpuOnly) {
    Write-Host "Installing CPU PyTorch..."
    & $venvPy -m pip install torch torchvision
} else {
    Write-Host "Installing CUDA 12.1 PyTorch (works on RTX 30/40 series)..."
    & $venvPy -m pip install torch torchvision --index-url https://download.pytorch.org/whl/cu121
}

Write-Host "Installing Ultralytics + ONNX deps..."
Write-Host "(Ultralytics auto-installs TensorFlow/onnx2tf on first TFLite export.)"
& $venvPy -m pip install -r (Join-Path $here "requirements.txt")

Write-Host "`nEnvironment ready."
& $venvPy -c "import torch, ultralytics; print('torch', torch.__version__, 'cuda', torch.cuda.is_available()); print('ultralytics', ultralytics.__version__)"
Write-Host "`nNext:"
Write-Host "  .\.venv\Scripts\python.exe convert_rdd_to_yolo.py --rdd ..\RDD2022_released_through_CRDDC2022\RDD2022\China_MotorBike --out dataset"
Write-Host "  .\.venv\Scripts\python.exe train.py"
Write-Host "  .\.venv\Scripts\python.exe export_tflite.py"
