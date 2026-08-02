#!/bin/bash
# ═══════════════════════════════════════════════════════════
#  WEAVER - Complete Installer (Linux/macOS)
#  Run once: ./install.sh
#  Installs everything. No manual steps needed.
# ═══════════════════════════════════════════════════════════

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WEAVER_HOME="$HOME/.weaver"
INSTALL_DIR="/usr/local/bin"

RED='\033[1;31m'
GREEN='\033[1;32m'
YELLOW='\033[1;33m'
CYAN='\033[1;36m'
DIM='\033[2m'
RESET='\033[0m'

# ─── Key setup function ──────────────────────────────────
setup_keys() {
    echo ""
    echo -e "  ${DIM}Weaver needs at least ONE free AI API key.${RESET}"
    echo -e "  ${DIM}All are free (no credit card). Press Enter to skip any.${RESET}"
    echo ""

    mkdir -p "$WEAVER_HOME"
    local CRED_FILE="$WEAVER_HOME/credentials.yml"
    echo "# Weaver API Credentials" > "$CRED_FILE"
    echo "" >> "$CRED_FILE"

    local KEY_COUNT=0
    local providers=("nvidia" "groq" "gemini" "cerebras" "mistral" "openrouter")
    local names=("NVIDIA NIM (RECOMMENDED)" "Groq" "Google Gemini" "Cerebras" "Mistral" "OpenRouter")
    local urls=("https://build.nvidia.com" "https://console.groq.com" "https://aistudio.google.com/apikey" "https://cloud.cerebras.ai" "https://console.mistral.ai" "https://openrouter.ai/keys")

    for i in "${!providers[@]}"; do
        echo -e "  \033[1m[${names[$i]}]\033[0m ${urls[$i]}"
        read -p "  API Key: " key
        if [ -n "$key" ]; then
            echo "${providers[$i]}:" >> "$CRED_FILE"
            echo "  api-key: $key" >> "$CRED_FILE"
            echo "" >> "$CRED_FILE"
            echo -e "  ${GREEN}✓ Saved${RESET}"
            KEY_COUNT=$((KEY_COUNT + 1))
        else
            echo -e "  ${DIM}  Skipped${RESET}"
        fi
        echo ""
    done

    chmod 600 "$CRED_FILE" 2>/dev/null || true

    if [ $KEY_COUNT -eq 0 ]; then
        echo -e "  ${RED}⚠ No keys provided. Run 'weaver' and use /configure later.${RESET}"
    else
        echo -e "  ${GREEN}✓ $KEY_COUNT API key(s) saved${RESET}"
    fi
}

# ─── Main ─────────────────────────────────────────────────

echo -e "${CYAN}"
echo "╦ ╦┌─┐┌─┐┬  ┬┌─┐┬─┐"
echo "║║║├┤ ├─┤└┐┌┘├┤ ├┬┘"
echo "╚╩╝└─┘┴ ┴ └┘ └─┘┴└─"
echo -e "${RESET}${DIM}  Complete Installer${RESET}"
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

# Detect OS
IS_LINUX=false
IS_MAC=false
if [[ "$OSTYPE" == "linux-gnu"* ]]; then
    IS_LINUX=true
elif [[ "$OSTYPE" == "darwin"* ]]; then
    IS_MAC=true
fi

# ─── Step 0: Clean old residues ───────────────────────────
echo -e "${CYAN}[0/7] Cleaning old installation...${RESET}"

# Remove old global symlink
sudo rm -f /usr/local/bin/weaver 2>/dev/null

# Remove old cached data (skills, ChromaDB cache, old logs, old venv)
rm -rf "$WEAVER_HOME/skills" 2>/dev/null
rm -rf "$WEAVER_HOME/venv" 2>/dev/null
rm -rf "$WEAVER_HOME/models" 2>/dev/null
rm -rf "$WEAVER_HOME/logs" 2>/dev/null
rm -f "$WEAVER_HOME/config.yml" 2>/dev/null

# Remove old Maven build artifacts
rm -rf "$SCRIPT_DIR/target" 2>/dev/null

# Keep credentials.yml (API keys) — user doesn't want to re-enter these
if [ -f "$WEAVER_HOME/credentials.yml" ]; then
    echo -e "  ${GREEN}✓${RESET} Keeping API keys (~/.weaver/credentials.yml)"
else
    echo -e "  ${DIM}  No previous keys found${RESET}"
fi

echo -e "  ${GREEN}✓${RESET} Old residues cleared"

# ─── Step 1: Java ─────────────────────────────────────────
echo -e "${CYAN}[1/7] Checking Java...${RESET}"
if command -v java &> /dev/null; then
    echo -e "  ${GREEN}✓${RESET} Java found"
else
    echo -e "  ${YELLOW}⚙ Installing Java 21...${RESET}"
    if $IS_LINUX; then
        sudo apt update -qq && sudo apt install -y openjdk-21-jdk 2>&1 | grep -E "^(Get|Setting)" | tail -3
    elif $IS_MAC; then
        brew install openjdk@21 2>&1 | tail -3 || { echo -e "${RED}  Install Java: brew install openjdk@21${RESET}"; exit 1; }
    fi
    echo -e "  ${GREEN}✓${RESET} Java installed"
fi

# ─── Step 2: Maven ────────────────────────────────────────
echo -e "${CYAN}[2/7] Checking Maven...${RESET}"
if command -v mvn &> /dev/null; then
    echo -e "  ${GREEN}✓${RESET} Maven found"
else
    echo -e "  ${YELLOW}⚙ Installing Maven...${RESET}"
    if $IS_LINUX; then
        sudo apt install -y maven 2>&1 | grep -E "^(Get|Setting)" | tail -3
    elif $IS_MAC; then
        brew install maven 2>&1 | tail -3 || { echo -e "${RED}  Install Maven: brew install maven${RESET}"; exit 1; }
    fi
    echo -e "  ${GREEN}✓${RESET} Maven installed"
fi

# ─── Step 3: Ollama + Qwen2 1.5B (Local Brain) ───────────
echo -e "${CYAN}[3/7] Setting up Local Brain (Ollama + Qwen2 1.5B)...${RESET}"

if command -v ollama &> /dev/null; then
    echo -e "  ${GREEN}✓${RESET} Ollama found"
else
    echo -e "  ${YELLOW}⚙ Installing Ollama (~100MB)...${RESET}"
    curl -fsSL https://ollama.com/install.sh | sh 2>&1 | tail -3
    echo -e "  ${GREEN}✓${RESET} Ollama installed"
fi

# Start Ollama service if not running
if ! ollama list &> /dev/null 2>&1; then
    echo -e "  ${DIM}Starting Ollama service...${RESET}"
    ollama serve &> /dev/null &
    sleep 3
fi

# Pull Qwen2 1.5B model if not already present (local brain for validation/routing)
if ollama list 2>/dev/null | grep -q "qwen2"; then
    echo -e "  ${GREEN}✓${RESET} Qwen2 model ready"
else
    echo -e "  ${DIM}Pulling Qwen2 1.5B Instruct model (~1.0GB, one-time)...${RESET}"
    ollama pull qwen2:1.5b-instruct 2>&1 | grep -E "pulling|success|verifying" | while read line; do
        echo -e "       ${DIM}$line${RESET}"
    done
    echo -e "  ${GREEN}✓${RESET} Qwen2 model pulled"
fi

# Remove old Gemma model if present (no longer needed)
if ollama list 2>/dev/null | grep -q "gemma3:270m"; then
    echo -e "  ${DIM}Removing old Gemma 270M model (replaced by Qwen2)...${RESET}"
    ollama rm gemma3:270m 2>/dev/null || true
    echo -e "  ${GREEN}✓${RESET} Old model removed"
fi

# ─── Step 4: Docker + ChromaDB (Semantic Cache) ───────────
echo -e "${CYAN}[4/7] Setting up ChromaDB (Semantic Cache)...${RESET}"

if command -v docker &> /dev/null; then
    echo -e "  ${GREEN}✓${RESET} Docker found"
    # Check if Docker daemon is running
    if docker info &> /dev/null 2>&1; then
        # Create and start ChromaDB container
        if docker ps -a --format '{{.Names}}' 2>/dev/null | grep -q "weaver-chroma"; then
            # Container exists — start it if stopped
            docker start weaver-chroma &> /dev/null
            echo -e "  ${GREEN}✓${RESET} ChromaDB container started"
        else
            # Create new container
            echo -e "  ${DIM}Pulling ChromaDB image (~200MB, one-time)...${RESET}"
            docker run -d --name weaver-chroma --restart unless-stopped \
                -p 8000:8000 chromadb/chroma:0.5.23 &> /dev/null
            echo -e "  ${GREEN}✓${RESET} ChromaDB container created and running"
        fi
    else
        echo -e "  ${YELLOW}⚠${RESET} Docker daemon not running. Start Docker and re-run install, or run:"
        echo -e "     ${DIM}sudo systemctl start docker${RESET}"
        echo -e "     ${DIM}docker run -d --name weaver-chroma --restart unless-stopped -p 8000:8000 chromadb/chroma:0.5.23${RESET}"
    fi
else
    echo -e "  ${YELLOW}⚠${RESET} Docker not found. Installing..."
    if $IS_LINUX; then
        # Install Docker on Linux
        if command -v apt &> /dev/null; then
            sudo apt update -qq && sudo apt install -y docker.io 2>&1 | tail -3
            sudo systemctl start docker 2>/dev/null
            sudo systemctl enable docker 2>/dev/null
            # Add current user to docker group (avoids needing sudo)
            sudo usermod -aG docker "$USER" 2>/dev/null
            echo -e "  ${GREEN}✓${RESET} Docker installed"
            # Start ChromaDB
            sudo docker run -d --name weaver-chroma --restart unless-stopped \
                -p 8000:8000 chromadb/chroma:0.5.23 &> /dev/null
            echo -e "  ${GREEN}✓${RESET} ChromaDB running on port 8000"
            echo -e "  ${DIM}  Note: Log out and back in for docker group to take effect${RESET}"
        else
            echo -e "  ${RED}✗${RESET} Cannot auto-install Docker. Install manually and re-run."
        fi
    elif $IS_MAC; then
        echo -e "  ${YELLOW}⚠${RESET} Install Docker Desktop from https://docker.com/products/docker-desktop"
        echo -e "  ${DIM}  ChromaDB is optional — Weaver works without it (no semantic cache)${RESET}"
    fi
fi

# ─── Step 5: Build Weaver ─────────────────────────────────
echo -e "${CYAN}[5/7] Building Weaver...${RESET}"
cd "$SCRIPT_DIR"
echo -e "  ${DIM}Downloading dependencies + compiling...${RESET}"
if mvn package -DskipTests 2>&1 | grep -E "Downloading|BUILD" | tail -10 | while read line; do
    echo -e "       ${DIM}$line${RESET}"
done; then
    if [ -f "$SCRIPT_DIR/target/weaver-agent-1.0.0-SNAPSHOT.jar" ]; then
        echo -e "  ${GREEN}✓${RESET} Build successful"
    else
        echo -e "  ${RED}✗ Build failed. Run 'mvn package' for details.${RESET}"
        exit 1
    fi
fi

# ─── Step 6: Install global command ───────────────────────
echo -e "${CYAN}[6/7] Installing 'weaver' command...${RESET}"
chmod +x "$SCRIPT_DIR/weaver"
chmod +x "$SCRIPT_DIR/scripts/"*.sh 2>/dev/null || true

if [ -w "$INSTALL_DIR" ]; then
    ln -sf "$SCRIPT_DIR/weaver" "$INSTALL_DIR/weaver"
else
    sudo ln -sf "$SCRIPT_DIR/weaver" "$INSTALL_DIR/weaver"
fi
echo -e "  ${GREEN}✓${RESET} 'weaver' available globally"

# ─── Step 7: API Keys ────────────────────────────────────
echo -e "${CYAN}[7/7] API Key Setup...${RESET}"
CREDENTIALS_FILE="$WEAVER_HOME/credentials.yml"

if [ -f "$CREDENTIALS_FILE" ] && grep "api-key:" "$CREDENTIALS_FILE" 2>/dev/null | grep -qv "YOUR_"; then
    echo -e "  ${GREEN}✓${RESET} Existing API keys found"
    # Check if NVIDIA key is missing (it's new and most important)
    if ! grep -q "nvidia:" "$CREDENTIALS_FILE" 2>/dev/null; then
        echo -e "  ${YELLOW}⚠${RESET} NVIDIA NIM key not found (recommended for best performance)"
        echo -e "  \033[1m[NVIDIA NIM (RECOMMENDED)]\033[0m https://build.nvidia.com"
        read -p "  API Key: " nvidia_key
        if [ -n "$nvidia_key" ]; then
            echo "" >> "$CREDENTIALS_FILE"
            echo "nvidia:" >> "$CREDENTIALS_FILE"
            echo "  api-key: $nvidia_key" >> "$CREDENTIALS_FILE"
            echo "" >> "$CREDENTIALS_FILE"
            echo -e "  ${GREEN}✓ NVIDIA key saved${RESET}"
        else
            echo -e "  ${DIM}  Skipped (you can add it later in ~/.weaver/credentials.yml)${RESET}"
        fi
    fi
else
    setup_keys
fi

# ─── Done ─────────────────────────────────────────────────
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo -e "${GREEN}  ✓ Weaver installed successfully!${RESET}"
echo ""
echo -e "  Run ${CYAN}weaver${RESET} from any directory to start."
echo ""
echo -e "${DIM}  Components installed:"
echo "    • Java 21 (runtime)"
echo "    • Weaver Agent (JAR)"
echo "    • Ollama + Qwen2 1.5B (local brain for validation/routing)"
echo "    • Docker + ChromaDB (semantic cache for solved problems)"
echo -e "    • API keys (~/.weaver/credentials.yml)${RESET}"
echo ""
echo -e "  ${DIM}On running 'weaver', the following start automatically:"
echo "    • Ollama (local model server)"
echo "    • ChromaDB (semantic cache on port 8000)"
echo -e "    • Weaver Agent (Spring Boot app)${RESET}"
echo ""
