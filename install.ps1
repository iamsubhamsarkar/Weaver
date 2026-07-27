# ═══════════════════════════════════════════════════════════
#  WEAVER INSTALLER (Windows PowerShell)
#  Run once: .\install.ps1
#  Then use 'weaver' from any directory.
# ═══════════════════════════════════════════════════════════

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition

Write-Host "Installing Weaver..." -ForegroundColor Cyan

# 1. Check Java
$java = Get-Command java -ErrorAction SilentlyContinue
if (-not $java) {
    Write-Host "[ERROR] Java 21+ required." -ForegroundColor Red
    Write-Host "  Install: winget install EclipseAdoptium.Temurin.21.JDK"
    Write-Host "  Or download from: https://adoptium.net/"
    exit 1
}
$javaVersion = java --version 2>&1 | Select-Object -First 1
Write-Host "  ✓ Java found: $javaVersion" -ForegroundColor Green

# 2. Check Maven
$mvn = Get-Command mvn -ErrorAction SilentlyContinue
if (-not $mvn) {
    Write-Host "[ERROR] Maven required for first build." -ForegroundColor Red
    Write-Host "  Install: winget install Apache.Maven"
    Write-Host "  Or download from: https://maven.apache.org/download.cgi"
    exit 1
}
Write-Host "  ✓ Maven found" -ForegroundColor Green

# 3. Build the project
Write-Host "  Building..." -ForegroundColor Yellow
Push-Location $ScriptDir
mvn package -DskipTests -q
if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] Build failed." -ForegroundColor Red
    Pop-Location
    exit 1
}
Pop-Location
Write-Host "  ✓ Build successful" -ForegroundColor Green

# 4. Add to PATH (user-level)
$weaverDir = $ScriptDir
$userPath = [System.Environment]::GetEnvironmentVariable("PATH", "User")

if ($userPath -notlike "*$weaverDir*") {
    [System.Environment]::SetEnvironmentVariable("PATH", "$userPath;$weaverDir", "User")
    Write-Host "  ✓ Added to user PATH: $weaverDir" -ForegroundColor Green
    Write-Host "    (Restart your terminal for PATH changes to take effect)" -ForegroundColor Yellow
} else {
    Write-Host "  ✓ Already in PATH" -ForegroundColor Green
}

Write-Host ""
Write-Host "Done! Run 'weaver' from any directory to start." -ForegroundColor Green
Write-Host "First run will ask for your free API keys (one-time setup)." -ForegroundColor Cyan
