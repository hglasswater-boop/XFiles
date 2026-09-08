#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")/../.." && pwd)
ENGINE_REPO=${RUST_SMB_REPO:-https://github.com/hglasswater-boop/smb-io-rs.git}
ENGINE_REF=${RUST_SMB_REF:-1730357aaa7ae142243743fa18574f60c3b4a4c3}
NDK_VERSION=${NDK_VERSION:-29.0.14206865}
CARGO_NDK_VERSION=${CARGO_NDK_VERSION:-4.1.2}
CARGO_NDK_PLATFORM=${CARGO_NDK_PLATFORM:-26}
WORK_DIR=${RUST_SMB_WORK_DIR:-${RUNNER_TEMP:-$ROOT_DIR/.gradle}/xfiles-smb-io-rs}
JNI_DIR="$ROOT_DIR/app/src/main/jniLibs"

for tool in git rustup cargo sdkmanager; do
    command -v "$tool" >/dev/null 2>&1 || {
        echo "Required tool not found: $tool" >&2
        exit 1
    }
done

rustup toolchain install stable --profile minimal --no-self-update >/dev/null
rustup target add \
    aarch64-linux-android \
    armv7-linux-androideabi \
    x86_64-linux-android >/dev/null

if ! cargo ndk --version 2>/dev/null | grep -q "${CARGO_NDK_VERSION}"; then
    cargo install cargo-ndk --version "$CARGO_NDK_VERSION" --locked
fi

sdkmanager "ndk;${NDK_VERSION}" >/dev/null
ANDROID_SDK_HOME=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
if [[ -z "$ANDROID_SDK_HOME" ]]; then
    echo "ANDROID_SDK_ROOT/ANDROID_HOME is not set" >&2
    exit 1
fi
export ANDROID_NDK_HOME="$ANDROID_SDK_HOME/ndk/$NDK_VERSION"

rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR"
git -C "$WORK_DIR" init -q
git -C "$WORK_DIR" remote add origin "$ENGINE_REPO"
git -C "$WORK_DIR" fetch --depth 1 origin "$ENGINE_REF"
git -C "$WORK_DIR" checkout --detach -q FETCH_HEAD

rm -rf "$JNI_DIR"
mkdir -p "$JNI_DIR"
(
    cd "$WORK_DIR"
    cargo ndk \
        --platform "$CARGO_NDK_PLATFORM" \
        -t arm64-v8a \
        -t armeabi-v7a \
        -t x86_64 \
        -o "$JNI_DIR" \
        build --release -p smb-io-android
)

LLVM_NM="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm"
[[ -x "$LLVM_NM" ]] || {
    echo "llvm-nm not found: $LLVM_NM" >&2
    exit 1
}

for ABI in arm64-v8a armeabi-v7a x86_64; do
    SO="$JNI_DIR/$ABI/libsmb_io_android.so"
    [[ -f "$SO" ]] || {
        echo "Rust SMB JNI library missing: $SO" >&2
        exit 1
    }
    "$LLVM_NM" -D "$SO" \
        | grep -q 'Java_app_local1st_files_core_fs_rust_RustSmbNative_nativePrefetch' || {
            echo "Rust SMB JNI symbol verification failed: $SO" >&2
            exit 1
        }
done

echo "Rust SMB JNI libraries staged from $ENGINE_REF"
