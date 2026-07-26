#!/bin/bash
# ═══════════════════════════════════════════════════════════
#  WEAVER INSTALLER
#  Run once: ./install.sh
#  Then use 'weaver' from anywhere on your system.
# ═══════════════════════════════════════════════════════════

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INSTALL_DIR="/usr/local/bin"

RED='\033[1;31m'
GREEN='\033[1;32m'
CYAN='\033[1;36m'
RESET='\033[0m'

echo -e "${CYAN}Installing Weaver...${RESET}"

# 1. Check Java
if ! command -v java &> /dev/null; then
    echo -e "${RED}Java 21+ required.${RESET}"
    if [[ "$OSTYPE" == "linux-gnu"* ]]; then
        echo "  Run: sudo apt install openjdk-21-jdk"
    elif [[ "$OSTYPE" == "darwin"* ]]; then
        echo "  Run: brew install openjdk@21"
    fi
    exit 1
fi
echo -e "  ${GREEN}✓${RESET} Java found: $(java --version 2>&1 | head -1)"

# 2. Check Maven
if ! command -v mvn &> /dev/null; then
    echo -e "${RED}Maven required for first build.${RESET}"
    if [[ "$OSTYPE" == "linux-gnu"* ]]; then
        echo "  Run: sudo apt install maven"
    elif [[ "$OSTYPE" == "darwin"* ]]; then
        echo "  Run: brew install maven"
    fi
    exit 1
fi
echo -e "  ${GREEN}✓${RESET} Maven found"

# 3. Build the project
echo -e "  Building..."
cd "$SCRIPT_DIR"
mvn package -DskipTests -q
echo -e "  ${GREEN}✓${RESET} Build successful"

# 4. Create symlink
if [ -w "$INSTALL_DIR" ]; then
    ln -sf "$SCRIPT_DIR/weaver" "$INSTALL_DIR/weaver"
else
    echo -e "  Need sudo to install to $INSTALL_DIR"
    sudo ln -sf "$SCRIPT_DIR/weaver" "$INSTALL_DIR/weaver"
fi
echo -e "  ${GREEN}✓${RESET} Installed to $INSTALL_DIR/weaver"

echo ""
echo -e "${GREEN}Done! Run 'weaver' from any directory to start.${RESET}"
echo -e "${CYAN}First run will ask for your free API keys (one-time setup).${RESET}"
