# ═══════════════════════════════════════════════════════════
#  WEAVER - Complete Installer (Windows PowerShell)
#  Run once: .\install.ps1
#  Installs everything. No manual steps needed.
# ═══════════════════════════════════════════════════════════

$ErrorActionPreference = "Continue"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$WeaverHome = "$env:USERPROFILE\.weaver"

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

# ─── Step 3: Ollama + Gemma (Local Brain) ─────────────────
Write-Host "[3/6] Setting up Local Brain (Ollama + Gemma)..." -ForegroundColor Cyan
$ollama = Get-Command ollama -ErrorAction SilentlyContinue
if (-not $ollama) {
    Write-Host "  Installing Ollama..." -ForegroundColor Yellow
    winget install Ollama.Ollama --accept-package-agreements --accept-source-agreements -h 2>$null
    $env:PATH = [System.Environment]::GetEnvironmentVariable("PATH", "Machine") + ";" + [System.Environment]::GetEnvironmentVariable("PATH", "User")
    Write-Host "  ✓ Ollama installed" -ForegroundColor Green
} else {
    Write-Host "  ✓ Ollama found" -ForegroundColor Green
}

# Start Ollama and pull model
Write-Host "  Pulling Gemma 3 1B model (~815MB, one-time)..." -ForegroundColor DarkGray
Start-Process ollama -ArgumentList "serve" -WindowStyle Hidden -ErrorAction SilentlyContinue
Start-Sleep -Seconds 3
& ollama pull gemma3:1b 2>$null
Write-Host "  ✓ Gemma model ready" -ForegroundColor Green

# ─── Step 4: Build Weaver ─────────────────────────────────
Write-Host "[4/6] Building Weaver..." -ForegroundColor Cyan
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

# ─── Step 5: Add to PATH ─────────────────────────────────
Write-Host "[5/6] Installing 'weaver' command..." -ForegroundColor Cyan
$userPath = [System.Environment]::GetEnvironmentVariable("PATH", "User")
if ($userPath -notlike "*$ScriptDir*") {
    [System.Environment]::SetEnvironmentVariable("PATH", "$userPath;$ScriptDir", "User")
    $env:PATH += ";$ScriptDir"
    Write-Host "  ✓ Added to PATH" -ForegroundColor Green
} else {
    Write-Host "  ✓ Already in PATH" -ForegroundColor Green
}

# ─── Step 6: API Keys ────────────────────────────────────
Write-Host "[6/6] API Key Setup..." -ForegroundColor Cyan
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
