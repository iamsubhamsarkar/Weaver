#!/bin/sh
# Maven Wrapper script - downloads Maven if not present and runs it
set -e

MAVEN_VERSION="3.9.9"
MAVEN_HOME="$HOME/.m2/wrapper/dists/apache-maven-$MAVEN_VERSION"
MAVEN_BIN="$MAVEN_HOME/bin/mvn"

if [ ! -f "$MAVEN_BIN" ]; then
    echo "Downloading Maven $MAVEN_VERSION..."
    mkdir -p "$MAVEN_HOME"
    DOWNLOAD_URL="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$MAVEN_VERSION/apache-maven-$MAVEN_VERSION-bin.tar.gz"
    curl -fsSL "$DOWNLOAD_URL" | tar xz -C "$HOME/.m2/wrapper/dists/" 2>/dev/null || {
        echo "Failed to download Maven. Install it manually: brew install maven"
        exit 1
    }
    # tar extracts to apache-maven-X.Y.Z, move contents
    if [ -d "$HOME/.m2/wrapper/dists/apache-maven-$MAVEN_VERSION" ]; then
        true # already in place
    fi
fi

if [ -f "$MAVEN_BIN" ]; then
    exec "$MAVEN_BIN" "$@"
else
    # Fallback: try system maven
    if command -v mvn >/dev/null 2>&1; then
        exec mvn "$@"
    else
        echo "Maven not found. Install with: brew install maven"
        exit 1
    fi
fi
