#!/usr/bin/env bash
# ============================================================================
# aod-bridge-apk / build.sh —— 零 Gradle 构建 LSPosed API 102 模块 APK
#                             （Linux / WSL / CI，与 CI 使用同一脚本）
#
# 流水线：javac(libxposed API 源码 + 注解桩) -> javac(模块源码) -> d8
#         -> aapt2 资源编译/链接 -> 组装(classes.dex + META-INF/xposed 三件套)
#         -> zipalign -> apksigner 签名(v1+v2+v3)
#
# 环境变量（均有默认值）：
#   VERSION_NAME / VERSION_CODE   版本名 / 版本号（默认 0.1.0 / 1）
#   OUT_FILE                      输出 APK 路径（默认 dist/aodbridge-v<版本名>.apk）
#   ANDROID_SDK_ROOT | ANDROID_HOME   Android SDK 位置
#   KEYSTORE / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD   签名配置
#   KEYSTORE_BASE64               密钥库 base64（CI 用，优先于 KEYSTORE）
#
# 需要：JDK 17+（javac/jar/keytool）、zip、Android SDK（platforms;android-36、
#       build-tools;36.x 提供 aapt2/d8/zipalign/apksigner）
# ============================================================================
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
version_name="${VERSION_NAME:-0.1.0}"
version_code="${VERSION_CODE:-1}"
out_file="${OUT_FILE:-$root/dist/aodbridge-v${version_name}.apk}"
build_dir="$root/build"

log() { printf '%s\n' "$*"; }
die() { printf '[x] %s\n' "$*" >&2; exit 1; }

for tool in javac jar keytool zip; do
  command -v "$tool" >/dev/null 2>&1 || die "缺少命令: $tool（安装 JDK 17+ 与 zip）"
done

# ---------- 定位 Android SDK ----------
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$sdk" ]; then
  for cand in "$root/../android-sdk" "$root/../../android-sdk" "$HOME/Android/Sdk"; do
    if [ -d "$cand" ]; then sdk="$(cd "$cand" && pwd)"; break; fi
  done
fi
if [ -z "$sdk" ] || [ ! -d "$sdk" ]; then
  die "找不到 Android SDK：请设置 ANDROID_SDK_ROOT"
fi

android_jar="$sdk/platforms/android-36/android.jar"
[ -f "$android_jar" ] || die "缺少 $android_jar（sdkmanager 安装 platforms;android-36）"

bt=""
for cand in "$sdk/build-tools/36.1.0" "$sdk/build-tools/36.0.0"; do
  if [ -x "$cand/aapt2" ]; then bt="$cand"; break; fi
done
if [ -z "$bt" ]; then
  bt="$(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -n 1 || true)"
fi
if [ -z "$bt" ] || [ ! -x "$bt/aapt2" ]; then
  die "找不到 build-tools（需要 aapt2/d8/zipalign/apksigner）"
fi
aapt2="$bt/aapt2"
d8="$bt/d8"
zipalign="$bt/zipalign"
apksigner="$bt/apksigner"

api_src="$root/third_party/libxposed-api/io"
stubs_src="$root/vendor/libxposed-stubs"
[ -d "$api_src" ] || die "缺少 libxposed API 源码目录: $api_src"

log "==> 清理构建目录"
rm -rf "$build_dir"
mkdir -p "$build_dir/apiClasses" "$build_dir/appClasses" "$build_dir/dex" "$(dirname "$out_file")"

log "==> [1/6] 编译 libxposed API 102（源码 + 注解桩，编译期使用）"
api_sources=()
while IFS= read -r -d '' f; do api_sources+=("$f"); done < <(find "$api_src" -name '*.java' -print0)
while IFS= read -r -d '' f; do api_sources+=("$f"); done < <(find "$stubs_src" -name '*.java' -print0)
javac -source 17 -target 17 -nowarn -classpath "$android_jar" -d "$build_dir/apiClasses" "${api_sources[@]}"
jar cf "$build_dir/libxposed-api.jar" -C "$build_dir/apiClasses" .

log "==> [2/6] 编译模块源码"
app_sources=()
while IFS= read -r -d '' f; do app_sources+=("$f"); done < <(find "$root/app/src/main/java" -name '*.java' -print0)
javac -source 17 -target 17 -nowarn \
  -classpath "$android_jar:$build_dir/libxposed-api.jar" \
  -d "$build_dir/appClasses" "${app_sources[@]}"
jar cf "$build_dir/app-classes.jar" -C "$build_dir/appClasses" .

log "==> [3/6] d8 -> classes.dex"
"$d8" --min-api 29 --lib "$android_jar" --classpath "$build_dir/libxposed-api.jar" \
  --output "$build_dir/dex" "$build_dir/app-classes.jar"

log "==> [4/6] aapt2 资源编译 / 链接"
"$aapt2" compile --dir "$root/app/src/main/res" -o "$build_dir/res.zip"
"$aapt2" link -o "$build_dir/base.apk" -I "$android_jar" \
  --manifest "$root/app/src/main/AndroidManifest.xml" \
  --min-sdk-version 29 --target-sdk-version 36 \
  --version-code "$version_code" --version-name "$version_name" \
  "$build_dir/res.zip"

log "==> [5/6] 组装 APK（classes.dex + META-INF/xposed 三件套）"
cp "$build_dir/base.apk" "$build_dir/unsigned.apk"
( cd "$build_dir/dex" && zip -q -X "$build_dir/unsigned.apk" classes.dex )
( cd "$root/app/src/main/resources" && zip -q -X "$build_dir/unsigned.apk" \
    META-INF/xposed/java_init.list META-INF/xposed/module.prop META-INF/xposed/scope.list )

log "==> [6/6] zipalign + apksigner 签名"
"$zipalign" -f 4 "$build_dir/unsigned.apk" "$build_dir/aligned.apk"

keystore="${KEYSTORE:-$root/aodbridge.keystore}"
store_pass="${KEYSTORE_PASSWORD:-aodbridge123}"
key_alias="${KEY_ALIAS:-aodbridge}"
key_pass="${KEY_PASSWORD:-$store_pass}"
if [ -n "${KEYSTORE_BASE64:-}" ]; then
  keystore="$build_dir/release.keystore"
  printf '%s' "$KEYSTORE_BASE64" | base64 -d > "$keystore"
  log "    签名密钥：来自 KEYSTORE_BASE64"
elif [ ! -f "$keystore" ]; then
  log "    生成签名用 keystore: $keystore"
  keytool -genkeypair -keystore "$keystore" -alias "$key_alias" -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$store_pass" -keypass "$key_pass" \
    -dname "CN=AOD Bridge, OU=dev, O=aodbridge, C=CN"
fi
"$apksigner" sign --ks "$keystore" --ks-pass "pass:$store_pass" --key-pass "pass:$key_pass" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$out_file" "$build_dir/aligned.apk"
"$apksigner" verify --print-certs "$out_file"

log ""
log "APK 构建完成: $out_file"
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "$out_file" | tee "$(dirname "$out_file")/SHA256SUMS.txt"
fi
