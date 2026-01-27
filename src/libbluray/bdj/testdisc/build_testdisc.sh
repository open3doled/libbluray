#!/bin/bash
#
# Build script for HAVI Test Disc
# Creates a complete Blu-ray disc structure for testing libbluray's BD-J/HAVI implementation
#
# Configuration is read from XML files in the config/ directory using HD Cookbook format.
# Only a subset of the full format is supported - unsupported features will error clearly.
#

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BDJ_DIR="$(dirname "$SCRIPT_DIR")"
JAVA_SRC="$BDJ_DIR/java"
TEST_SRC="$SCRIPT_DIR/java"
CONFIG_DIR="$SCRIPT_DIR/config"
OUTPUT_DIR="$SCRIPT_DIR/disc"
BUILD_DIR="$SCRIPT_DIR/build"
TOOLS_BUILD="$SCRIPT_DIR/tools_build"

echo "=== HAVI Test Disc Builder ==="
echo "BDJ source: $JAVA_SRC"
echo "Config dir: $CONFIG_DIR"
echo "Output dir: $OUTPUT_DIR"
echo ""

# Clean previous build
rm -rf "$BUILD_DIR"
rm -rf "$TOOLS_BUILD"
rm -rf "$OUTPUT_DIR"
mkdir -p "$BUILD_DIR"
mkdir -p "$TOOLS_BUILD"

# Create disc directory structure
echo "Creating disc structure..."
mkdir -p "$OUTPUT_DIR/BDMV/BDJO"
mkdir -p "$OUTPUT_DIR/BDMV/JAR"
mkdir -p "$OUTPUT_DIR/BDMV/PLAYLIST"
mkdir -p "$OUTPUT_DIR/BDMV/CLIPINF"
mkdir -p "$OUTPUT_DIR/BDMV/STREAM"
mkdir -p "$OUTPUT_DIR/BDMV/BACKUP/BDJO"
mkdir -p "$OUTPUT_DIR/CERTIFICATE/BACKUP"

# Step 1: Compile the build tools (XML parsers + legacy writers)
echo ""
echo "Step 1: Compiling build tools..."

javac -d "$TOOLS_BUILD" \
    "$TEST_SRC/org/videolan/test/BdjoXmlParser.java" \
    "$TEST_SRC/org/videolan/test/IndexXmlParser.java" \
    "$TEST_SRC/org/videolan/test/MovieObjectXmlParser.java" 2>&1 || {
        echo "Error: Could not compile build tools"
        exit 1
    }

echo "Build tools compiled."

# Step 2: Compile the test Xlet
# First, try to find libbluray.jar (from build directory or system)
echo ""
echo "Step 2: Compiling test Xlet..."

LIBBLURAY_JAR=""
# Try to find libbluray.jar from build directory
for jar in "$BDJ_DIR/../../../build/src/libbluray/bdj/libbluray-j2se"*.jar \
           "$BDJ_DIR/../../../build/src/libbluray/bdj/libbluray"*.jar \
           "$BDJ_DIR/../../.libs/libbluray"*.jar; do
    if [ -f "$jar" ]; then
        LIBBLURAY_JAR="$jar"
        break
    fi
done

if [ -n "$LIBBLURAY_JAR" ]; then
    echo "Using libbluray.jar: $LIBBLURAY_JAR"
    
    # Compile the test Xlet against libbluray.jar
    javac -source 1.8 -target 1.8 \
        -cp "$LIBBLURAY_JAR" \
        -d "$BUILD_DIR" \
        -Xlint:none \
        "$TEST_SRC/org/videolan/test/HaviTestXlet.java" 2>&1
else
    echo "No libbluray.jar found. Attempting source compilation..."
    
    # Try to compile against source (may fail if dependencies are missing)
    javac -source 1.8 -target 1.8 \
        -sourcepath "$JAVA_SRC" \
        -d "$BUILD_DIR" \
        -Xlint:none \
        -XDignore.symbol.file \
        "$TEST_SRC/org/videolan/test/HaviTestXlet.java" 2>&1 || true
fi

if [ ! -f "$BUILD_DIR/org/videolan/test/HaviTestXlet.class" ]; then
    echo "Error: Failed to compile HaviTestXlet.java"
    echo ""
    echo "Please build libbluray first:"
    echo "  cd /path/to/libbluray"
    echo "  mkdir build && cd build"
    echo "  ../configure --enable-bdj"
    echo "  make"
    echo ""
    echo "Or ensure libbluray.jar is available in the build directory."
    exit 1
fi

echo "Test Xlet compiled."

# Step 3: Create the JAR file containing ONLY the test Xlet
# The HAVI classes are provided by libbluray.jar at runtime
echo ""
echo "Step 3: Creating JAR file..."

cd "$BUILD_DIR"
jar cf "$OUTPUT_DIR/BDMV/JAR/00000.jar" org/videolan/test/HaviTestXlet.class

echo "Created: $OUTPUT_DIR/BDMV/JAR/00000.jar"
jar tf "$OUTPUT_DIR/BDMV/JAR/00000.jar"

# Step 4: Generate BDJO file from XML config
echo ""
echo "Step 4: Generating BDJO file from XML config..."
echo "Using XML config: $CONFIG_DIR/00000.bdjo.xml"
java -cp "$TOOLS_BUILD" org.videolan.test.BdjoXmlParser \
    "$CONFIG_DIR/00000.bdjo.xml" \
    "$OUTPUT_DIR/BDMV/BDJO/00000.bdjo"

# Copy to backup
cp "$OUTPUT_DIR/BDMV/BDJO/00000.bdjo" "$OUTPUT_DIR/BDMV/BACKUP/BDJO/"

# Step 5: Generate index.bdmv from XML config
echo ""
echo "Step 5: Generating index.bdmv from XML config..."
echo "Using XML config: $CONFIG_DIR/index.xml"
java -cp "$TOOLS_BUILD" org.videolan.test.IndexXmlParser \
    "$CONFIG_DIR/index.xml" \
    "$OUTPUT_DIR/BDMV/index.bdmv"

# Step 6: Generate MovieObject.bdmv from XML config
echo ""
echo "Step 6: Generating MovieObject.bdmv from XML config..."
echo "Using XML config: $CONFIG_DIR/movieobject.xml"
java -cp "$TOOLS_BUILD" org.videolan.test.MovieObjectXmlParser \
    "$CONFIG_DIR/movieobject.xml" \
    "$OUTPUT_DIR/BDMV/MovieObject.bdmv"

# Step 7: Create minimal required files
echo ""
echo "Step 7: Creating additional required files..."

# Create empty id.bdmv (disc ID file)
# Format: BDID0100 + 32 bytes content
printf 'BDID0100' > "$OUTPUT_DIR/CERTIFICATE/id.bdmv"
# Add 32 bytes of zeros for the content
dd if=/dev/zero bs=1 count=32 >> "$OUTPUT_DIR/CERTIFICATE/id.bdmv" 2>/dev/null

# Create app.discroot.crt (minimal certificate placeholder)
touch "$OUTPUT_DIR/CERTIFICATE/app.discroot.crt"

echo ""
echo "=== Build Complete ==="
echo ""
echo "Disc structure created at: $OUTPUT_DIR"
echo ""
echo "Configuration files used:"
echo "  BDJO: $CONFIG_DIR/00000.bdjo.xml"
echo "  Index: $CONFIG_DIR/index.xml"
echo "  MovieObject: $CONFIG_DIR/movieobject.xml"
echo ""
echo "Directory contents:"
find "$OUTPUT_DIR" -type f | sort | while read f; do
    size=$(wc -c < "$f" | tr -d ' ')
    echo "  $f ($size bytes)"
done

echo ""
echo "To test with libbluray, use a BD-J player pointing to:"
echo "  $OUTPUT_DIR"
echo ""
echo "For VLC: vlc bluray://$OUTPUT_DIR"
