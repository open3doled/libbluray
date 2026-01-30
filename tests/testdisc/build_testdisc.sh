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
# Navigate from tests/testdisc to repo root
REPO_ROOT="$(dirname "$(dirname "$SCRIPT_DIR")")"
BDJ_DIR="$REPO_ROOT/src/libbluray/bdj"
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

# Step 2: Compile all test Xlets
# First, try to find libbluray.jar (from build directory or system)
echo ""
echo "Step 2: Compiling all test Xlets..."

LIBBLURAY_JAR=""
# Try to find libbluray.jar from build directory
for jar in "$REPO_ROOT/build/src/libbluray/bdj/libbluray-j2se"*.jar \
           "$REPO_ROOT/build/src/libbluray/bdj/libbluray"*.jar \
           "$BDJ_DIR/../.libs/libbluray"*.jar; do
    if [ -f "$jar" ]; then
        LIBBLURAY_JAR="$jar"
        break
    fi
done

# Find all *Xlet.java files
XLET_FILES=$(find "$TEST_SRC" -name "*Xlet.java" -type f)
XLET_COUNT=$(echo "$XLET_FILES" | wc -l | tr -d ' ')
echo "Found $XLET_COUNT Xlet file(s) to compile"

if [ -n "$LIBBLURAY_JAR" ]; then
    echo "Using libbluray.jar: $LIBBLURAY_JAR"
    
    # Compile all test Xlets against libbluray.jar
    javac -source 1.8 -target 1.8 \
        -cp "$LIBBLURAY_JAR" \
        -d "$BUILD_DIR" \
        -Xlint:none \
        $XLET_FILES 2>&1
else
    echo "No libbluray.jar found. Attempting source compilation..."
    
    # Try to compile against source (may fail if dependencies are missing)
    javac -source 1.8 -target 1.8 \
        -sourcepath "$JAVA_SRC" \
        -d "$BUILD_DIR" \
        -Xlint:none \
        -XDignore.symbol.file \
        $XLET_FILES 2>&1 || true
fi

# Check that at least one xlet compiled
COMPILED_XLETS=$(find "$BUILD_DIR" -name "*Xlet.class" -type f 2>/dev/null | wc -l | tr -d ' ')
if [ "$COMPILED_XLETS" -eq 0 ]; then
    echo "Error: Failed to compile any Xlet files"
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

echo "Compiled $COMPILED_XLETS Xlet class(es)."

# Step 3: Create the JAR file containing ALL test Xlets
# The HAVI classes are provided by libbluray.jar at runtime
echo ""
echo "Step 3: Creating JAR file..."

cd "$BUILD_DIR"
# Include all compiled xlet classes and their inner classes in the JAR
jar cf "$OUTPUT_DIR/BDMV/JAR/00000.jar" org/videolan/test/*.class

echo "Created: $OUTPUT_DIR/BDMV/JAR/00000.jar"
echo "JAR contents:"
jar tf "$OUTPUT_DIR/BDMV/JAR/00000.jar"

# Step 4: Generate all BDJO files from XML configs
echo ""
echo "Step 4: Generating BDJO files from XML configs..."

BDJO_COUNT=0
for bdjo_xml in "$CONFIG_DIR"/*.bdjo.xml; do
    if [ -f "$bdjo_xml" ]; then
        bdjo_name=$(basename "$bdjo_xml" .bdjo.xml)
        echo "  Processing: $bdjo_xml -> ${bdjo_name}.bdjo"
        java -cp "$TOOLS_BUILD" org.videolan.test.BdjoXmlParser \
            "$bdjo_xml" \
            "$OUTPUT_DIR/BDMV/BDJO/${bdjo_name}.bdjo"
        
        # Copy to backup
        cp "$OUTPUT_DIR/BDMV/BDJO/${bdjo_name}.bdjo" "$OUTPUT_DIR/BDMV/BACKUP/BDJO/"
        BDJO_COUNT=$((BDJO_COUNT + 1))
    fi
done

echo "Generated $BDJO_COUNT BDJO file(s)."

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
echo "  BDJO configs: $CONFIG_DIR/*.bdjo.xml"
echo "  Index: $CONFIG_DIR/index.xml"
echo "  MovieObject: $CONFIG_DIR/movieobject.xml"
echo ""
echo "Xlets compiled: $COMPILED_XLETS"
echo "BDJO files generated: $BDJO_COUNT"
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
