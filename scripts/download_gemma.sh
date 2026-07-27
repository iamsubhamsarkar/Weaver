#!/bin/bash
# ═══════════════════════════════════════════════════════════
#  Download Gemma 3 270M for local smart pre-processing
#  Run once: bash scripts/download_gemma.sh
#  Requires: python3, python3-venv
# ═══════════════════════════════════════════════════════════

set -e

MODELS_DIR="$HOME/.weaver/models"
GEMMA_DIR="$MODELS_DIR/gemma-3-270m-it"
VENV_DIR="$HOME/.weaver/venv"

echo "═══ Weaver Local Brain Setup ═══"
echo ""

# Check Python
if ! command -v python3 &> /dev/null; then
    echo "❌ Python3 required. Install with:"
    echo "   sudo apt install python3 python3-venv python3-full"
    exit 1
fi

# Ensure venv module is available
if ! python3 -m venv --help &> /dev/null 2>&1; then
    echo "❌ python3-venv required. Install with:"
    echo "   sudo apt install python3-venv python3-full"
    exit 1
fi

# Create virtual environment
if [ ! -d "$VENV_DIR" ]; then
    echo "📦 Creating Python virtual environment at $VENV_DIR..."
    python3 -m venv "$VENV_DIR"
fi

# Activate venv
source "$VENV_DIR/bin/activate"

# Install dependencies inside venv
echo "📦 Installing Python dependencies (inside venv)..."
pip install -q --upgrade pip
pip install -q transformers torch --index-url https://download.pytorch.org/whl/cpu

# Create models directory
mkdir -p "$MODELS_DIR"

# Download model
echo ""
echo "⬇️  Downloading Gemma 3 270M (instruction-tuned)..."
echo "   This is ~540MB and only needs to happen once."
echo ""

python3 -c "
from transformers import AutoTokenizer, AutoModelForCausalLM
import os

model_id = 'google/gemma-3-270m-it'
save_path = os.path.expanduser('~/.weaver/models/gemma-3-270m-it')
os.makedirs(save_path, exist_ok=True)

print('  Downloading tokenizer...')
tokenizer = AutoTokenizer.from_pretrained(model_id)
tokenizer.save_pretrained(save_path)

print('  Downloading model weights...')
model = AutoModelForCausalLM.from_pretrained(model_id, torch_dtype='auto')
model.save_pretrained(save_path)

print('  ✓ Model saved to:', save_path)
"

# Create the runner script (uses the venv Python)
echo "📝 Creating inference runner..."
cat > "$MODELS_DIR/run_gemma.py" << PYTHON_SCRIPT
#!/usr/bin/env python3
"""
Lightweight Gemma 270M inference runner for Weaver.
Called as subprocess: ~/.weaver/venv/bin/python3 run_gemma.py "prompt text"
Outputs generated text to stdout.
"""
import sys
import os

# Suppress warnings
os.environ["TOKENIZERS_PARALLELISM"] = "false"
import warnings
warnings.filterwarnings("ignore")

from transformers import AutoTokenizer, AutoModelForCausalLM
import torch

MODEL_PATH = os.path.expanduser("~/.weaver/models/gemma-3-270m-it")

def generate(prompt, max_new_tokens=60):
    tokenizer = AutoTokenizer.from_pretrained(MODEL_PATH)
    model = AutoModelForCausalLM.from_pretrained(MODEL_PATH, torch_dtype=torch.float32)
    model.eval()

    # Format as instruction
    formatted = f"<start_of_turn>user\n{prompt}<end_of_turn>\n<start_of_turn>model\n"

    inputs = tokenizer(formatted, return_tensors="pt")
    with torch.no_grad():
        outputs = model.generate(
            **inputs,
            max_new_tokens=max_new_tokens,
            do_sample=False,
            temperature=0.1
        )

    result = tokenizer.decode(outputs[0][inputs['input_ids'].shape[1]:], skip_special_tokens=True)
    print(result.strip())

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python3 run_gemma.py 'prompt'")
        sys.exit(1)
    generate(sys.argv[1])
PYTHON_SCRIPT

chmod +x "$MODELS_DIR/run_gemma.py"

# Deactivate venv
deactivate

echo ""
echo "═══════════════════════════════════════════════════"
echo "  ✓ Gemma 3 270M installed successfully!"
echo "  Location: $GEMMA_DIR"
echo "  Venv: $VENV_DIR"
echo ""
echo "  Weaver will now use it for:"
echo "    • Smart search query extraction"
echo "    • Context summarization"
echo "    • Task classification"
echo "═══════════════════════════════════════════════════"
