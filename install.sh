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
echo -e "${CYAN}[0/6] Cleaning old installation...${RESET}"

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
echo -e "${CYAN}[1/6] Checking Java...${RESET}"
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
echo -e "${CYAN}[2/6] Checking Maven...${RESET}"
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

# ─── Step 3: Ollama + MiniCPM5-1B Q8 (Local Brain) ───────
echo -e "${CYAN}[3/6] Setting up Local Brain (Ollama + MiniCPM5-1B Q8)...${RESET}"

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

# Pull MiniCPM5 model if not already present (replaces old Gemma 270M)
if ollama list 2>/dev/null | grep -q "minicpm5"; then
    echo -e "  ${GREEN}✓${RESET} MiniCPM5 model ready"
else
    echo -e "  ${DIM}Pulling MiniCPM5-1B Q8 model (~1.1GB, one-time)...${RESET}"
    ollama pull minicpm5:1b-q8_0 2>&1 | grep -E "pulling|success|verifying" | while read line; do
        echo -e "       ${DIM}$line${RESET}"
    done
    echo -e "  ${GREEN}✓${RESET} MiniCPM5 model pulled"
fi

# Remove old Gemma model if present (no longer needed)
if ollama list 2>/dev/null | grep -q "gemma3:270m"; then
    echo -e "  ${DIM}Removing old Gemma 270M model (replaced by MiniCPM5)...${RESET}"
    ollama rm gemma3:270m 2>/dev/null || true
    echo -e "  ${GREEN}✓${RESET} Old model removed"
fi

# ─── Step 4: Build Weaver ─────────────────────────────────
echo -e "${CYAN}[4/6] Building Weaver...${RESET}"
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

# ─── Step 5: Install global command ───────────────────────
echo -e "${CYAN}[5/6] Installing 'weaver' command...${RESET}"
chmod +x "$SCRIPT_DIR/weaver"
chmod +x "$SCRIPT_DIR/scripts/"*.sh 2>/dev/null || true

if [ -w "$INSTALL_DIR" ]; then
    ln -sf "$SCRIPT_DIR/weaver" "$INSTALL_DIR/weaver"
else
    sudo ln -sf "$SCRIPT_DIR/weaver" "$INSTALL_DIR/weaver"
fi
echo -e "  ${GREEN}✓${RESET} 'weaver' available globally"

# ─── Step 6: API Keys ────────────────────────────────────
echo -e "${CYAN}[6/6] API Key Setup...${RESET}"
CREDENTIALS_FILE="$WEAVER_HOME/credentials.yml"

if [ -f "$CREDENTIALS_FILE" ] && grep "api-key:" "$CREDENTIALS_FILE" 2>/dev/null | grep -qv "YOUR_"; then
    echo -e "  ${GREEN}✓${RESET} API keys already configured"
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
echo "    • Ollama (local AI runtime)"
echo "    • MiniCPM5-1B Q8 (local brain for validation/routing)"
echo -e "    • API keys (~/.weaver/credentials.yml)${RESET}"
echo ""
