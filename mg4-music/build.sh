#!/usr/bin/env bash
# Rebuilds the MG4 head unit's stock Music app (com.saicmotor.hmi.music) and media
# service (com.saicmotor.service.media) from the APKs pulled off the car, so the
# online-music tab and the native player talk to Metrolist's package instead of
# Amazon Music's. Only the package is patched: Metrolist declares the activity and
# service the stock apps expect (com.saicmotor.onlinemedia.MediaAppActivity and
# .service.PlayService). The vendor's code is never committed: decode and build
# output stay in the git-ignored work/ folder.
#
# Required:
#   SOURCE_APK        stock Music_my24_P.apk
#   APKTOOL_JAR       apktool 2.12+ jar
#   BUILD_TOOLS       Android SDK build-tools dir (zipalign, lib/apksigner.jar)
# Optional:
#   WORK_DIR          where decoded sources and APKs live (default: ./work). Point it
#                     outside this repository to keep the vendor's code off GitHub.
#   REUSE_DECODED     1 to rebuild from the existing <name>-decoded folders in WORK_DIR
#                     without decoding or patching again, so manual smali edits survive.
#   SOURCE_MEDIA_APK  stock MediaService_my24_P.apk (also builds media-mg4.apk)
#   METROLIST_PACKAGE package the car launches and binds (default com.metrolist.music.mg4)
# Signing, one of:
#   SIGN_KEY + SIGN_CERT   PKCS#8 key and X.509 cert (the stock apps use AOSP's
#                          public platform key, so the car accepts the update)
#   SIGN_KEYSTORE          keystore (default ~/.android/debug.keystore, with
#                          SIGN_KEY_ALIAS / SIGN_KEYSTORE_PASS)
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
work="${WORK_DIR:-$here/work}"
reuse="${REUSE_DECODED:-0}"

: "${APKTOOL_JAR:?set APKTOOL_JAR to apktool.jar}"
[[ "$reuse" == 1 ]] || : "${SOURCE_APK:?set SOURCE_APK to the stock Music_my24_P.apk}"
: "${BUILD_TOOLS:?set BUILD_TOOLS to an Android SDK build-tools directory}"
package="${METROLIST_PACKAGE:-com.metrolist.music.mg4}"
apksigner=(java -jar "$BUILD_TOOLS/lib/apksigner.jar")

# Replaces a smali string constant, failing when it is not found so a different
# stock version does not silently ship unpatched.
replace_const() {
  local file="$1" old="$2" new="$3"
  grep -q "const-string [a-z0-9]*, \"$old\"" "$file" || { echo "missing \"$old\" in $file" >&2; exit 1; }
  sed -i "s|\(const-string [a-z0-9]*, \"\)$old\"|\1$new\"|" "$file"
}

# Both stock apps pick the online-music package from the car model (Joox or Amazon).
patch_package() {
  replace_const "$1" "com.saicmotor.onlinemedia.joox" "$package"
  replace_const "$1" "com.saicmotor.onlinemedia.amazon.v2" "$package"
}

sign() {
  if [[ -n "${SIGN_KEY:-}" ]]; then
    : "${SIGN_CERT:?set SIGN_CERT with SIGN_KEY}"
    "${apksigner[@]}" sign --key "$SIGN_KEY" --cert "$SIGN_CERT" --out "$2" "$1"
  else
    local storepass="${SIGN_KEYSTORE_PASS:-android}"
    "${apksigner[@]}" sign \
      --ks "${SIGN_KEYSTORE:-$HOME/.android/debug.keystore}" \
      --ks-key-alias "${SIGN_KEY_ALIAS:-androiddebugkey}" \
      --ks-pass "pass:$storepass" --key-pass "pass:$storepass" \
      --out "$2" "$1"
  fi
}

# build_app <name> <stock apk> <smali file to patch, relative to the decoded dir>
build_app() {
  local name="$1" source="$2" smali="$3" decoded="$work/$1-decoded"
  rm -f "$work/$name-unsigned.apk" "$work/$name-aligned.apk" "$work/$name-mg4.apk"
  if [[ "$reuse" == 1 ]]; then
    [[ -d "$decoded" ]] || { echo "no $decoded to reuse" >&2; exit 1; }
  else
    rm -rf "$decoded"
    java -jar "$APKTOOL_JAR" d -f -o "$decoded" "$source"
    patch_package "$decoded/$smali"
  fi
  java -jar "$APKTOOL_JAR" b -o "$work/$name-unsigned.apk" "$decoded"
  "$BUILD_TOOLS/zipalign" -f -p 4 "$work/$name-unsigned.apk" "$work/$name-aligned.apk"
  sign "$work/$name-aligned.apk" "$work/$name-mg4.apk"
  "${apksigner[@]}" verify --print-certs "$work/$name-mg4.apk"
  echo "Built $work/$name-mg4.apk"
}

mkdir -p "$work"
build_app music "${SOURCE_APK:-}" smali/d1/b.smali
if [[ -n "${SOURCE_MEDIA_APK:-}" || ( "$reuse" == 1 && -d "$work/media-decoded" ) ]]; then
  build_app media "${SOURCE_MEDIA_APK:-}" smali/o0/b.smali
fi
