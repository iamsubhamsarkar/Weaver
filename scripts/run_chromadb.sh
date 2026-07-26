#!/bin/bash
# Start ChromaDB for Weaver's semantic cache (Experience Library)
echo "🚀 Starting ChromaDB..."

docker run -d \
  --name weaver-chroma \
  --restart unless-stopped \
  -p 8000:8000 \
  -v "$(pwd)/chromadb-data:/chroma/chroma" \
  chromadb/chroma:latest

echo "✓ ChromaDB started at http://localhost:8000"
echo "  Collection: weaver-experience"
echo ""
echo "To stop: docker stop weaver-chroma"
echo "To remove: docker rm weaver-chroma"
