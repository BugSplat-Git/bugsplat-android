#!/bin/bash
#
# Build Crashpad for Android with 16KB page size support
# This script builds crashpad_handler for all Android ABIs using libcurl for HTTP transport
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
CRASHPAD_DIR="$ROOT_DIR/third_party/crashpad"
LIBCURL_DIR="$ROOT_DIR/third_party/libcurl-android"
OUTPUT_DIR="$ROOT_DIR/app/src/main/cpp/crashpad"

# Default NDK path - can be overridden via environment variable
DEFAULT_NDK_PATH="$HOME/Library/Android/sdk/ndk/28.2.13676358"
ANDROID_NDK="${ANDROID_NDK:-$DEFAULT_NDK_PATH}"

# Android API level
# Note: API level 26+ is required for __system_property_read_callback
# The built libraries will still work on older devices as long as the
# code paths using this API aren't executed on those devices
ANDROID_API_LEVEL=26

# ABIs to build
ABIS=("arm64-v8a" "armeabi-v7a" "x86_64")

# Function to map Android ABI to crashpad target_cpu value
# (Using a function instead of associative array for macOS bash 3.2 compatibility)
abi_to_cpu() {
    case "$1" in
        "arm64-v8a") echo "arm64" ;;
        "armeabi-v7a") echo "arm" ;;
        "x86_64") echo "x64" ;;
        *) echo "unknown" ;;
    esac
}

echo "=============================================="
echo "Building Crashpad for Android"
echo "=============================================="
echo "NDK Path: $ANDROID_NDK"
echo "Source: $CRASHPAD_DIR"
echo "Output: $OUTPUT_DIR"
echo "API Level: $ANDROID_API_LEVEL"
echo "16KB page size: enabled"
echo "=============================================="

# Validate NDK path
if [ ! -d "$ANDROID_NDK" ]; then
    echo "Error: Android NDK not found at $ANDROID_NDK"
    echo "Please set ANDROID_NDK environment variable or install NDK 27.2.12479018"
    exit 1
fi

# Verify depot_tools commands are available (gn and gclient)
if ! command -v gn &> /dev/null; then
    echo "Error: 'gn' command not found."
    echo "Please install depot_tools and add it to your PATH:"
    echo "  git clone https://chromium.googlesource.com/chromium/tools/depot_tools.git ~/depot_tools"
    echo "  export PATH=\$HOME/depot_tools:\$PATH"
    exit 1
fi

if ! command -v gclient &> /dev/null; then
    echo "Error: 'gclient' command not found."
    echo "Please install depot_tools and add it to your PATH:"
    echo "  git clone https://chromium.googlesource.com/chromium/tools/depot_tools.git ~/depot_tools"
    echo "  export PATH=\$HOME/depot_tools:\$PATH"
    exit 1
fi

echo "depot_tools: $(which gn | xargs dirname)"

# Initialize submodule if needed
if [ ! -f "$CRASHPAD_DIR/BUILD.gn" ]; then
    echo "Initializing crashpad submodule..."
    cd "$ROOT_DIR"
    git submodule update --init third_party/crashpad
fi

cd "$CRASHPAD_DIR"

# Fetch crashpad dependencies using gclient
echo "Fetching crashpad dependencies..."
if [ ! -f ".gclient" ]; then
    # Create a .gclient file for standalone crashpad checkout
    cat > "$CRASHPAD_DIR/../.gclient" << EOF
solutions = [
  {
    "name": "crashpad",
    "url": "https://github.com/chromium/crashpad.git",
    "managed": False,
    "custom_deps": {},
  },
]
EOF
fi

# Run gclient sync to fetch dependencies (mini_chromium, etc.)
cd "$CRASHPAD_DIR/.."
gclient sync --no-history

cd "$CRASHPAD_DIR"

# Build for each ABI
for ABI in "${ABIS[@]}"; do
    TARGET_CPU="$(abi_to_cpu "$ABI")"
    BUILD_DIR="out/android_${ABI}"
    
    echo ""
    echo "=============================================="
    echo "Building for ABI: $ABI (target_cpu=$TARGET_CPU)"
    echo "=============================================="
    
    # Find libcurl for this ABI
    CURL_DIR="$LIBCURL_DIR/build/curl/$ABI"
    CURL_INCLUDE="$LIBCURL_DIR/curl/include"
    
    if [ ! -d "$CURL_DIR" ]; then
        echo "Warning: libcurl not found for $ABI at $CURL_DIR"
        echo "Please run build-libcurl.sh first"
        CURL_FLAGS=""
    else
        # Get the installed curl include path
        if [ -d "$CURL_DIR/include" ]; then
            CURL_INCLUDE="$CURL_DIR/include"
        fi
        CURL_FLAGS="extra_cflags=\"-I$CURL_INCLUDE\""
    fi
    
    # Create args.gn with 16KB page size support
    mkdir -p "$BUILD_DIR"
    cat > "$BUILD_DIR/args.gn" << EOF
# Android target configuration
target_os = "android"
target_cpu = "$TARGET_CPU"

# NDK configuration
android_ndk_root = "$ANDROID_NDK"
android_api_level = $ANDROID_API_LEVEL

# Use libcurl for HTTP transport (required for crash uploads)
crashpad_http_transport_impl = "libcurl"

# Include path for libcurl headers
extra_cflags = "-I$CURL_INCLUDE"

# Linker flags:
# - static-libstdc++: Required for Android to avoid libstdc++ dependency issues
# - max-page-size=16384: Enable 16KB page size support for Android 15+
extra_ldflags = "-static-libstdc++ -Wl,-z,max-page-size=16384"

# Release build for smaller binary size
is_debug = false
EOF

    echo "Generated args.gn:"
    cat "$BUILD_DIR/args.gn"
    echo ""
    
    # Generate ninja files
    echo "Running gn gen..."
    gn gen "$BUILD_DIR"
    
    # Build crashpad_handler and client libraries
    echo "Building crashpad..."
    ninja -C "$BUILD_DIR" \
        handler:crashpad_handler \
        client:client \
        client:common \
        util:util \
        third_party/mini_chromium/mini_chromium/base:base
    
    # Copy outputs to the output directory
    echo "Copying outputs..."
    mkdir -p "$OUTPUT_DIR/lib/$ABI/client"
    mkdir -p "$OUTPUT_DIR/lib/$ABI/util"
    mkdir -p "$OUTPUT_DIR/lib/$ABI/base"
    
    # Copy crashpad_handler executable
    # Note: On Android, crashpad_handler runs as a separate process, not a shared library
    if [ -f "$BUILD_DIR/crashpad_handler" ]; then
        cp "$BUILD_DIR/crashpad_handler" "$OUTPUT_DIR/lib/$ABI/libcrashpad_handler.so"
        echo "  Copied crashpad_handler -> libcrashpad_handler.so"
    elif [ -f "$BUILD_DIR/exe.unstripped/crashpad_handler" ]; then
        cp "$BUILD_DIR/exe.unstripped/crashpad_handler" "$OUTPUT_DIR/lib/$ABI/libcrashpad_handler.so"
        echo "  Copied crashpad_handler (unstripped) -> libcrashpad_handler.so"
    fi
    
    # Copy static libraries - try multiple possible paths
    for LIB_PATH in "$BUILD_DIR/obj/client/libclient.a" "$BUILD_DIR/libclient.a"; do
        if [ -f "$LIB_PATH" ]; then
            cp "$LIB_PATH" "$OUTPUT_DIR/lib/$ABI/client/"
            echo "  Copied libclient.a"
            break
        fi
    done
    
    for LIB_PATH in "$BUILD_DIR/obj/client/libcommon.a" "$BUILD_DIR/libcommon.a"; do
        if [ -f "$LIB_PATH" ]; then
            cp "$LIB_PATH" "$OUTPUT_DIR/lib/$ABI/client/"
            echo "  Copied libcommon.a"
            break
        fi
    done
    
    for LIB_PATH in "$BUILD_DIR/obj/util/libutil.a" "$BUILD_DIR/libutil.a"; do
        if [ -f "$LIB_PATH" ]; then
            cp "$LIB_PATH" "$OUTPUT_DIR/lib/$ABI/util/"
            echo "  Copied libutil.a"
            break
        fi
    done
    
    for LIB_PATH in "$BUILD_DIR/obj/third_party/mini_chromium/mini_chromium/base/libbase.a" "$BUILD_DIR/libbase.a"; do
        if [ -f "$LIB_PATH" ]; then
            cp "$LIB_PATH" "$OUTPUT_DIR/lib/$ABI/base/"
            echo "  Copied libbase.a"
            break
        fi
    done
    
    echo "Build complete for $ABI"
done

# Copy headers (only need to do this once)
echo ""
echo "Copying headers..."
mkdir -p "$OUTPUT_DIR/include"
rsync -a --include='*.h' --include='*/' --exclude='*' \
    "$CRASHPAD_DIR/client/" "$OUTPUT_DIR/include/client/"
rsync -a --include='*.h' --include='*/' --exclude='*' \
    "$CRASHPAD_DIR/util/" "$OUTPUT_DIR/include/util/"
rsync -a --include='*.h' --include='*/' --exclude='*' \
    "$CRASHPAD_DIR/third_party/mini_chromium/mini_chromium/" "$OUTPUT_DIR/include/third_party/mini_chromium/mini_chromium/"
rsync -a --include='*.h' --include='*/' --exclude='*' \
    "$CRASHPAD_DIR/snapshot/" "$OUTPUT_DIR/include/snapshot/"
rsync -a --include='*.h' --include='*/' --exclude='*' \
    "$CRASHPAD_DIR/minidump/" "$OUTPUT_DIR/include/minidump/"

echo ""
echo "=============================================="
echo "Crashpad build complete!"
echo "=============================================="

# Verify 16KB alignment
echo ""
echo "Verifying 16KB alignment..."
LLVM_OBJDUMP="$ANDROID_NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-objdump"
if [ ! -f "$LLVM_OBJDUMP" ]; then
    # Try Linux path
    LLVM_OBJDUMP="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objdump"
fi

for ABI in "${ABIS[@]}"; do
    HANDLER="$OUTPUT_DIR/lib/$ABI/libcrashpad_handler.so"
    if [ -f "$HANDLER" ]; then
        echo "Checking $ABI/libcrashpad_handler.so:"
        "$LLVM_OBJDUMP" -p "$HANDLER" 2>/dev/null | grep LOAD || echo "  Could not verify alignment"
    fi
done
