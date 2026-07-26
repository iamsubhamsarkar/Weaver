#!/bin/bash
# Health check for Weaver Agent and its dependencies

echo "=== Weaver Health Check ==="
echo ""

# Check Weaver API
echo -n "Weaver API:   "
if curl -s http://localhost:8080/api/agent/health > /dev/null 2>&1; then
    RESPONSE=$(curl -s http://localhost:8080/api/agent/health)
    echo "✓ UP - $RESPONSE"
else
    echo "✗ DOWN"
fi

# Check ChromaDB
echo -n "ChromaDB:     "
if curl -s http://localhost:8000/api/v1/heartbeat > /dev/null 2>&1; then
    echo "✓ UP"
else
    echo "✗ DOWN (run: bash scripts/run_chromadb.sh)"
fi

# Check providers
echo ""
echo "Providers:"
curl -s http://localhost:8080/api/agent/providers 2>/dev/null | python3 -m json.tool 2>/dev/null || echo "  (Weaver not running)"
