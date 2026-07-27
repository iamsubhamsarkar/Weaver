# ═══════════════════════════════════════════════════════════
#  WEAVER - Complete Installer (Windows PowerShell)
#  Run once: .\install.ps1
#  Installs everything. No manual steps needed.
# ═══════════════════════════════════════════════════════════

$ErrorActionPreference = "Continue"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$WeaverHome = "$env:USERPROFILE\.weaver"
$VenvDir = "$WeaverHome\venv"
$ModelsDir = "$WeaverHome\models"
$GemmaPath = "$ModelsDir\gemma-3-270m-it"

Write-Host ""
Write-Host "  WEAVER - Complete Installer" -ForegroundColor Cyan
Write-Host "  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor DarkGray
Write-Host ""

# ─── Step 1: Java ─────────────────────────────────────────
Write-Host "[1/7] Checking Java..." -ForegroundColor Cyan
$java = Get-Command java -ErrorAction SilentlyContinue
if (-not $java) {
    Write-Host "  Installing Java 21..." -ForegroundColor Yellow
    winget install EclipseAdoptium.Temurin.21.JDK --accept-package-agreements --accept-source-agreements -h 2>$null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  Please install Java manually: https://adoptium.net/" -ForegroundColor Red
        exit 1
    }
    # Refresh PATH
    $env:PATH = [System.Environment]::GetEnvironmentVariable("PATH", "Machine") + ";" + [System.Environment]::GetEnvironmentVariable("PATH", "User")
    Write-Host "  ✓ Java installed" -ForegroundColor Green
} else {
    Write-Host "  ✓ Java found" -ForegroundColor Green
}

# ─── Step 2: Maven ────────────────────────────────────────
Write-Host "[2/7] Checking Maven..." -ForegroundColor Cyan
$mvn = Get-Command mvn -ErrorAction SilentlyContinue
if (-not $mvn) {
    Write-Host "  Installing Maven..." -ForegroundColor Yellow
    winget install Apache.Maven --accept-package-agreements --accept-source-agreements -h 2>$null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  Please install Maven manually: https://maven.apache.org/" -ForegroundColor Red
        exit 1
    }
    $env:PATH = [System.Environment]::GetEnvironmentVariable("PATH", "Machine") + ";" + [System.Environment]::GetEnvironmentVariable("PATH", "User")
    Write-Host "  ✓ Maven installed" -ForegroundColor Green
} else {
    Write-Host "  ✓ Maven found" -ForegroundColor Green
}

# ─── Step 3: Python + venv ────────────────────────────────
Write-Host "[3/7] Setting up Python environment..." -ForegroundColor Cyan
$python = Get-Command python -ErrorAction SilentlyContinue
if (-not $python) {
    $python = Get-Command python3 -ErrorAction SilentlyContinue
}
if (-not $python) {
    Write-Host "  Installing Python..." -ForegroundColor Yellow
    winget install Python.Python.3.12 --accept-package-agreements --accept-source-agreements -h 2>$null
    $env:PATH = [System.Environment]::GetEnvironmentVariable("PATH", "Machine") + ";" + [System.Environment]::GetEnvironmentVariable("PATH", "User")
}

if (-not (Test-Path $VenvDir)) {
    python -m venv $VenvDir 2>$null
    if (-not (Test-Path $VenvDir)) { python3 -m venv $VenvDir 2>$null }
}
Write-Host "  ✓ Python environment ready" -ForegroundColor Green

# ─── Step 4: Local Brain (Gemma 270M) ────────────────────
Write-Host "[4/7] Setting up Local Brain (Gemma 270M, ~540MB)..." -ForegroundColor Cyan

if (Test-Path "$GemmaPath\config.json") {
    Write-Host "  ✓ Gemma 270M already installed" -ForegroundColor Green
} else {
    Write-Host "  Downloading model (one-time, ~540MB)..." -ForegroundColor DarkGray
    New-Item -ItemType Directory -Path $ModelsDir -Force | Out-Null

    & "$VenvDir\Scripts\pip.exe" install -q --upgrade pip 2>$null
    & "$VenvDir\Scripts\pip.exe" install -q transformers torch --index-url https://download.pytorch.org/whl/cpu 2>$null

    & "$VenvDir\Scripts\python.exe" -c @"
from transformers import AutoTokenizer, AutoModelForCausalLM
import os
model_id = 'google/gemma-3-270m-it'
save_path = os.path.expanduser('~/.weaver/models/gemma-3-270m-it')
os.makedirs(save_path, exist_ok=True)
print('    Downloading tokenizer...')
tokenizer = AutoTokenizer.from_pretrained(model_id)
tokenizer.save_pretrained(save_path)
print('    Downloading model weights...')
model = AutoModelForCausalLM.from_pretrained(model_id, torch_dtype='auto')
model.save_pretrained(save_path)
"@

    # Create runner script
    @"
import sys, os
os.environ["TOKENIZERS_PARALLELISM"] = "false"
import warnings; warnings.filterwarnings("ignore")
from transformers import AutoTokenizer, AutoModelForCausalLM
import torch
MODEL_PATH = os.path.expanduser("~/.weaver/models/gemma-3-270m-it")
def generate(prompt):
    tokenizer = AutoTokenizer.from_pretrained(MODEL_PATH)
    model = AutoModelForCausalLM.from_pretrained(MODEL_PATH, torch_dtype=torch.float32)
    model.eval()
    formatted = f"<start_of_turn>user\n{prompt}<end_of_turn>\n<start_of_turn>model\n"
    inputs = tokenizer(formatted, return_tensors="pt")
    with torch.no_grad():
        outputs = model.generate(**inputs, max_new_tokens=60, do_sample=False)
    print(tokenizer.decode(outputs[0][inputs['input_ids'].shape[1]:], skip_special_tokens=True).strip())
if __name__ == "__main__":
    if len(sys.argv) >= 2: generate(sys.argv[1])
"@ | Out-File -FilePath "$ModelsDir\run_gemma.py" -Encoding UTF8

    Write-Host "  ✓ Gemma 270M installed" -ForegroundColor Green
}

# ─── Step 5: Build Weaver ─────────────────────────────────
Write-Host "[5/7] Building Weaver..." -ForegroundColor Cyan
Push-Location $ScriptDir
mvn package -DskipTests -q 2>$null
if (Test-Path "$ScriptDir\target\weaver-agent-1.0.0-SNAPSHOT.jar") {
    Write-Host "  ✓ Build successful" -ForegroundColor Green
} else {
    Write-Host "  Build failed. Run 'mvn package' for details." -ForegroundColor Red
    Pop-Location
    exit 1
}
Pop-Location

# ─── Step 6: Add to PATH ─────────────────────────────────
Write-Host "[6/7] Installing 'weaver' command..." -ForegroundColor Cyan
$userPath = [System.Environment]::GetEnvironmentVariable("PATH", "User")
if ($userPath -notlike "*$ScriptDir*") {
    [System.Environment]::SetEnvironmentVariable("PATH", "$userPath;$ScriptDir", "User")
    $env:PATH += ";$ScriptDir"
    Write-Host "  ✓ Added to PATH" -ForegroundColor Green
} else {
    Write-Host "  ✓ Already in PATH" -ForegroundColor Green
}

# ─── Step 7: API Keys ────────────────────────────────────
Write-Host "[7/7] API Key Setup..." -ForegroundColor Cyan
$CredFile = "$WeaverHome\credentials.yml"

if ((Test-Path $CredFile) -and (Select-String -Path $CredFile -Pattern "api-key:" -Quiet)) {
    Write-Host "  ✓ API keys already configured" -ForegroundColor Green
} else {
    Write-Host ""
    Write-Host "  Weaver needs at least ONE free AI API key." -ForegroundColor DarkGray
    Write-Host "  All are free (no credit card). Press Enter to skip any." -ForegroundColor DarkGray
    Write-Host ""

    New-Item -ItemType Directory -Path $WeaverHome -Force | Out-Null
    "# Weaver API Credentials`n" | Out-File -FilePath $CredFile -Encoding UTF8

    $providers = @(
        @{id="groq"; name="Groq"; url="https://console.groq.com"},
        @{id="gemini"; name="Google Gemini"; url="https://aistudio.google.com/apikey"},
        @{id="cerebras"; name="Cerebras"; url="https://cloud.cerebras.ai"},
        @{id="mistral"; name="Mistral"; url="https://console.mistral.ai"},
        @{id="openrouter"; name="OpenRouter"; url="https://openrouter.ai/keys"}
    )

    $keyCount = 0
    foreach ($p in $providers) {
        Write-Host "  [$($p.name)] $($p.url)" -ForegroundColor White
        $key = Read-Host "  API Key"
        if ($key) {
            "$($p.id):`n  api-key: $key`n" | Add-Content -Path $CredFile
            Write-Host "  ✓ Saved" -ForegroundColor Green
            $keyCount++
        } else {
            Write-Host "    Skipped" -ForegroundColor DarkGray
        }
        Write-Host ""
    }

    if ($keyCount -eq 0) {
        Write-Host "  ⚠ No keys provided. Run 'weaver' and use /configure later." -ForegroundColor Yellow
    } else {
        Write-Host "  ✓ $keyCount key(s) saved" -ForegroundColor Green
    }
}

# ─── Done ─────────────────────────────────────────────────
Write-Host ""
Write-Host "  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor DarkGray
Write-Host ""
Write-Host "  ✓ Weaver installed successfully!" -ForegroundColor Green
Write-Host ""
Write-Host "  Run 'weaver' from any directory to start." -ForegroundColor Cyan
Write-Host "  (Restart terminal if 'weaver' is not recognized)" -ForegroundColor DarkGray
Write-Host ""
