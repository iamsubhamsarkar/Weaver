@echo off
REM ═══════════════════════════════════════════════════════════
REM  WEAVER - One-command launcher (Windows)
REM  Just run: weaver.bat
REM ═══════════════════════════════════════════════════════════

setlocal enabledelayedexpansion

set SCRIPT_DIR=%~dp0
set JAR_FILE=%SCRIPT_DIR%target\weaver-agent-1.0.0-SNAPSHOT.jar

REM ─── Step 1: Check Java ───────────────────────────────────
where java >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Java not found!
    echo   Install from: https://adoptium.net/
    echo   Or run: winget install EclipseAdoptium.Temurin.21.JDK
    exit /b 1
)

REM ─── Step 2: Build if needed ──────────────────────────────
if not exist "%JAR_FILE%" (
    echo Building Weaver (first time only^)...
    
    where mvn >nul 2>&1
    if %errorlevel% equ 0 (
        cd /d "%SCRIPT_DIR%" && mvn package -DskipTests -q
    ) else if exist "%SCRIPT_DIR%mvnw.cmd" (
        cd /d "%SCRIPT_DIR%" && mvnw.cmd package -DskipTests -q
    ) else (
        echo [ERROR] Maven not found!
        echo   Install from: https://maven.apache.org/download.cgi
        echo   Or run: winget install Apache.Maven
        exit /b 1
    )

    if not exist "%JAR_FILE%" (
        echo [ERROR] Build failed. Run 'mvn package' manually to see errors.
        exit /b 1
    )
    echo [OK] Build complete
)

REM ─── Step 3: Start ChromaDB if Docker available ───────────
where docker >nul 2>&1
if %errorlevel% equ 0 (
    docker ps --format "{{.Names}}" 2>nul | findstr /i "weaver-chroma" >nul 2>&1
    if %errorlevel% neq 0 (
        docker start weaver-chroma >nul 2>&1 || docker run -d --name weaver-chroma --restart unless-stopped -p 8000:8000 chromadb/chroma:0.5.23 >nul 2>&1
    )
)

REM ─── Step 4: Launch ───────────────────────────────────────
set USER_PWD=%CD%
cd /d "%SCRIPT_DIR%"
java -Dweaver.workspace="%USER_PWD%" -jar "%JAR_FILE%" --spring.profiles.active=local %*
