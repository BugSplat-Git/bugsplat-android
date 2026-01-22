#!/bin/bash
#
# Build all native dependencies for BugSplat Android SDK
# This script builds libcurl and crashpad with 16KB page size support
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "=============================================="
echo "BugSplat Android SDK - Native Build"
echo "=============================================="
echo ""
echo "This script will build:"
echo "  1. libcurl (with BoringSSL) for Android"
echo "  2. Crashpad for Android"
echo ""
echo "Both will be compiled with 16KB page size support"
echo "for Android 15+ compatibility."
echo ""
echo "=============================================="
echo ""

# Check prerequisites
echo "Checking prerequisites..."

# Check for Android NDK
DEFAULT_NDK_PATH="$HOME/Library/Android/sdk/ndk/28.2.13676358"
ANDROID_NDK="${ANDROID_NDK:-$DEFAULT_NDK_PATH}"
if [ ! -d "$ANDROID_NDK" ]; then
    echo "Error: Android NDK not found at $ANDROID_NDK"
    echo ""
    echo "Please install Android NDK 27.2.12479018 or set ANDROID_NDK environment variable."
    echo "You can install it via Android Studio SDK Manager or using sdkmanager:"
    echo "  sdkmanager --install 'ndk;27.2.12479018'"
    exit 1
fi
echo "  ✓ Android NDK: $ANDROID_NDK"

# Check for depot_tools (required for crashpad) by checking if gclient is available
if ! command -v gclient &> /dev/null; then
    echo "Error: gclient not found in PATH"
    echo ""
    echo "Please install depot_tools and add it to your PATH:"
    echo "  git clone https://chromium.googlesource.com/chromium/tools/depot_tools.git ~/depot_tools"
    echo "  export PATH=\$HOME/depot_tools:\$PATH"
    exit 1
fi
echo "  ✓ depot_tools: $(which gclient | xargs dirname)"

# Check for CMake
if ! command -v cmake &> /dev/null; then
    echo "Error: cmake not found"
    echo "Please install CMake: brew install cmake"
    exit 1
fi
echo "  ✓ CMake: $(cmake --version | head -n1)"

# Check for Ninja
if ! command -v ninja &> /dev/null; then
    echo "Error: ninja not found"
    echo "Please install Ninja: brew install ninja"
    exit 1
fi
echo "  ✓ Ninja: $(ninja --version)"

echo ""
echo "All prerequisites satisfied!"
echo ""

# Initialize submodules
echo "Initializing submodules..."
cd "$SCRIPT_DIR/.."
git submodule update --init --recursive
echo ""

# Build libcurl
echo "=============================================="
echo "Step 1: Building libcurl"
echo "=============================================="
"$SCRIPT_DIR/build-libcurl.sh"

echo ""

# Build crashpad
echo "=============================================="
echo "Step 2: Building Crashpad"
echo "=============================================="
"$SCRIPT_DIR/build-crashpad.sh"

echo ""
echo "=============================================="
echo "Build Complete!"
echo "=============================================="
echo ""
echo "Native libraries have been built and copied to:"
echo "  app/src/main/cpp/crashpad/lib/"
echo ""
echo "The following ABIs are supported:"
echo "  - arm64-v8a"
echo "  - armeabi-v7a"
echo "  - x86_64"
echo ""
echo "All libraries are built with 16KB page size alignment"
echo "for Android 15+ compatibility."
echo ""
