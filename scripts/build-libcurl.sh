#!/bin/bash
#
# Build libcurl for Android with 16KB page size support
# This script builds libcurl (with BoringSSL) for all Android ABIs
#
# Note: 16KB page size support is built into libcurl-android's build scripts
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
LIBCURL_DIR="$ROOT_DIR/third_party/libcurl-android"
OUTPUT_DIR="$ROOT_DIR/app/src/main/cpp/crashpad/lib"

# Default NDK path - can be overridden via environment variable
DEFAULT_NDK_PATH="$HOME/Library/Android/sdk/ndk/28.2.13676358"
ANDROID_NDK="${ANDROID_NDK:-$DEFAULT_NDK_PATH}"

echo "=============================================="
echo "Building libcurl for Android"
echo "=============================================="
echo "NDK Path: $ANDROID_NDK"
echo "Source: $LIBCURL_DIR"
echo "Output: $OUTPUT_DIR"
echo "16KB page size: enabled (built into libcurl-android)"
echo "=============================================="

# Validate NDK path
if [ ! -d "$ANDROID_NDK" ]; then
    echo "Error: Android NDK not found at $ANDROID_NDK"
    echo "Please set ANDROID_NDK environment variable or install NDK 27.2.12479018"
    exit 1
fi

# Initialize submodules if needed
if [ ! -f "$LIBCURL_DIR/build.sh" ]; then
    echo "Initializing libcurl-android submodule..."
    cd "$ROOT_DIR"
    git submodule update --init --recursive third_party/libcurl-android
fi

# Navigate to libcurl-android directory
cd "$LIBCURL_DIR"

# Update the NDK path in build.sh
echo "Configuring NDK path..."
sed -i.bak "s|export ANDROID_NDK=.*|export ANDROID_NDK=\"$ANDROID_NDK\"|" build.sh
rm -f build.sh.bak

# Run the build
echo "Building BoringSSL and libcurl..."
./build.sh

# Copy results to output directory
echo "Copying libcurl to output directory..."
ABIS=("arm64-v8a" "armeabi-v7a" "x86_64")
for ABI in "${ABIS[@]}"; do
    mkdir -p "$OUTPUT_DIR/$ABI"
    if [ -f "build/curl/$ABI/lib/libcurl.so" ]; then
        cp "build/curl/$ABI/lib/libcurl.so" "$OUTPUT_DIR/$ABI/"
        echo "  Copied libcurl.so for $ABI"
    else
        echo "  Warning: libcurl.so not found for $ABI"
    fi
done

echo ""
echo "=============================================="
echo "libcurl build complete!"
echo "=============================================="

# Verify 16KB alignment
echo ""
echo "Verifying 16KB alignment..."

# Determine the correct llvm-objdump path based on OS
if [[ "$OSTYPE" == "darwin"* ]]; then
    LLVM_OBJDUMP="$ANDROID_NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-objdump"
else
    LLVM_OBJDUMP="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objdump"
fi

for ABI in "${ABIS[@]}"; do
    SO_FILE="$OUTPUT_DIR/$ABI/libcurl.so"
    if [ -f "$SO_FILE" ]; then
        echo "Checking $ABI/libcurl.so:"
        "$LLVM_OBJDUMP" -p "$SO_FILE" 2>/dev/null | grep LOAD || echo "  Could not verify alignment"
    fi
done
