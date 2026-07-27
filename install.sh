#!/bin/bash
# ═══════════════════════════════════════════════════════════
#  WEAVER - Complete Installer (Linux/macOS)
#  Run once: ./install.sh
#  Installs everything. No manual steps needed.
# ═══════════════════════════════════════════════════════════

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WEAVER_HOME="$HOME/.weaver"
VENV_DIR="$WEAVER_HOME/venv"
MODELS_DIR="$WEAVER_HOME/models"
INSTALL_DIR="/usr/local/bin"

RED='\033[1;31m'
GREEN='\033[1;32m'
YELLOW='\033[1;33m'
CYAN='\033[1;36m'
DIM='\033[2m'
RESET='\033[0m'

# ─── Functions ────────────────────────────────────────────

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
    local providers=("groq" "gemini" "cerebras" "mistral" "openrouter")
    local names=("Groq" "Google Gemini" "Cerebras" "Mistral" "OpenRouter")
    local urls=("https://console.groq.com" "https://aistudio.google.com/apikey" "https://cloud.cerebras.ai" "https://console.mistral.ai" "https://openrouter.ai/keys")

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

# ─── Step 1: Java ─────────────────────────────────────────
echo -e "${CYAN}[1/7] Checking Java...${RESET}"
if command -v java &> /dev/null; then
    echo -e "  ${GREEN}✓${RESET} Java found"
else
    echo -e "  ${YELLOW}⚙ Installing Java 21...${RESET}"
    if $IS_LINUX; then
        sudo apt update -qq && sudo apt install -y -qq openjdk-21-jdk > /dev/null 2>&1
    elif $IS_MAC; then
        brew install openjdk@21 2>/dev/null || { echo -e "${RED}  Install Java: brew install openjdk@21${RESET}"; exit 1; }
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
        sudo apt install -y -qq maven > /dev/null 2>&1
    elif $IS_MAC; then
        brew install maven 2>/dev/null || { echo -e "${RED}  Install Maven: brew install maven${RESET}"; exit 1; }
    fi
    echo -e "  ${GREEN}✓${RESET} Maven installed"
fi

# ─── Step 3: Python + venv ────────────────────────────────
echo -e "${CYAN}[3/7] Setting up Python environment...${RESET}"
if ! command -v python3 &> /dev/null; then
    echo -e "  ${YELLOW}⚙ Installing Python3...${RESET}"
    if $IS_LINUX; then
        sudo apt install -y -qq python3 python3-venv python3-full > /dev/null 2>&1
    elif $IS_MAC; then
        brew install python3 2>/dev/null
    fi
fi

# Ensure venv module
if $IS_LINUX; then
    python3 -m venv --help > /dev/null 2>&1 || sudo apt install -y -qq python3-venv python3-full > /dev/null 2>&1
fi

# Create venv
if [ ! -d "$VENV_DIR" ]; then
    python3 -m venv "$VENV_DIR"
fi
echo -e "  ${GREEN}✓${RESET} Python environment ready"

# ─── Step 4: Local Brain (Gemma 270M) ────────────────────
echo -e "${CYAN}[4/7] Setting up Local Brain (Gemma 270M, ~540MB)...${RESET}"
GEMMA_PATH="$MODELS_DIR/gemma-3-270m-it"

if [ -d "$GEMMA_PATH" ] && [ "$(ls -A $GEMMA_PATH 2>/dev/null)" ]; then
    echo -e "  ${GREEN}✓${RESET} Gemma 270M already installed"
else
    echo -e "  ${DIM}  Downloading model (one-time, ~540MB)...${RESET}"
    mkdir -p "$MODELS_DIR"

    source "$VENV_DIR/bin/activate"
    pip install -q --upgrade pip 2>/dev/null
    pip install -q transformers torch --index-url https://download.pytorch.org/whl/cpu 2>/dev/null

    python3 << 'PYEOF'
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
PYEOF

    # Create inference runner
    cat > "$MODELS_DIR/run_gemma.py" << 'PYEOF'
#!/usr/bin/env python3
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
PYEOF
    chmod +x "$MODELS_DIR/run_gemma.py"
    deactivate 2>/dev/null || true

    echo -e "  ${GREEN}✓${RESET} Gemma 270M installed"
fi

# ─── Step 5: Build Weaver ─────────────────────────────────
echo -e "${CYAN}[5/7] Building Weaver...${RESET}"
cd "$SCRIPT_DIR"
if mvn package -DskipTests -q 2>&1 | tail -3; then
    if [ -f "$SCRIPT_DIR/target/weaver-agent-1.0.0-SNAPSHOT.jar" ]; then
        echo -e "  ${GREEN}✓${RESET} Build successful"
    else
        echo -e "  ${RED}✗ Build failed${RESET}"
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
echo "    • Java 21"
echo "    • Weaver Agent (JAR)"
echo "    • Gemma 270M local brain (~540MB)"
echo "    • Python venv (for local inference)"
echo -e "    • API keys (~/.weaver/credentials.yml)${RESET}"
echo ""
