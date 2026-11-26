#!/usr/bin/env bash
set -euo pipefail

project_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
sdk_dir=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [[ -z $sdk_dir ]]; then
    printf '%s\n' 'Set ANDROID_HOME to an Android SDK with platform 35 and Build-Tools 35.0.0.' >&2
    exit 1
fi

if [[ $# != 1 ]]; then
    printf 'Usage: %s output.apk\n' "${0##*/}" >&2
    exit 1
fi

output=$(realpath -m -- "$1")
sdk_dir=$(realpath -- "$sdk_dir")
tools="$sdk_dir/build-tools/35.0.0"
platform="$sdk_dir/platforms/android-35/android.jar"
if [[ -n ${JAVA_HOME:-} ]]; then
    javac="$JAVA_HOME/bin/javac"
    jar="$JAVA_HOME/bin/jar"
else
    javac=javac
    jar=jar
fi

scratch=$(mktemp -d)
trap 'rm -rf -- "$scratch"' EXIT
mkdir -p "$scratch/classes" "$scratch/generated" "$scratch/dex" "$(dirname -- "$output")"

# Gradle normally adds the application ID and SDK levels during manifest merging.
sed 's/<manifest xmlns:android=/<manifest package="org.kodiscraper.app" xmlns:android=/' \
    "$project_dir/app/src/main/AndroidManifest.xml" > "$scratch/AndroidManifest.xml"
"$tools/aapt2" compile --dir "$project_dir/app/src/main/res" -o "$scratch/resources.zip"
"$tools/aapt2" link -I "$platform" --manifest "$scratch/AndroidManifest.xml" \
    --java "$scratch/generated" --min-sdk-version 26 --target-sdk-version 35 \
    --version-code 4 --version-name 1.2.0 -o "$scratch/staging.apk" "$scratch/resources.zip"

mapfile -d '' sources < <(find "$project_dir/app/src/main/java" "$scratch/generated" -name '*.java' -print0)
"$javac" --release 17 -encoding UTF-8 -classpath "$platform" -d "$scratch/classes" "${sources[@]}"
"$jar" --create --file "$scratch/classes.jar" -C "$scratch/classes" .
"$tools/d8" --release --min-api 26 --lib "$platform" --output "$scratch/dex" "$scratch/classes.jar"
(cd -- "$scratch/dex" && zip -q -X -0 "$scratch/staging.apk" classes*.dex)
"$tools/zipalign" -f -P 16 4 "$scratch/staging.apk" "$output"
