#!/bin/sh
set -eu

project=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
build=$project/build
deps=$build/dependencies
android=$deps/android.jar
xposed=$deps/xposed-api-82.jar
keystore=$build/flymepicker.p12

mkdir -p "$build"
case ${1:-} in
  compile)
    compile_dir=$(mktemp -d)
    trap 'rm -rf "$compile_dir"' EXIT
    mkdir "$compile_dir/classes"
    (cd "$compile_dir" && jar xf "$deps/aapt2.jar" aapt2 && chmod +x aapt2)
    cd "$project"
    javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
      -classpath "$android:$xposed" -d "$compile_dir/classes" \
      src/dev/codex/flymepicker/FolderAlbums.java
    (cd "$compile_dir/classes" && jar cf "$build/classes.jar" dev)
    "$compile_dir/aapt2" link -I "$android" --manifest AndroidManifest.xml \
      -A assets -o "$build/manifest.apk"
    javac -encoding UTF-8 -classpath "$deps/apksig.jar" -d "$build" tools/SignApk.java
    ;;
  package)
    # This D8 release supports API 34 code generation. The manifest sets the install minimum.
    java -cp "$deps/r8.jar" com.android.tools.r8.D8 --min-api 34 --lib "$android" \
      --classpath "$xposed" --output "$build/dex.zip" "$build/classes.jar"

    password_file=$build/signing-password
    password=${FLYME_PICKER_KEY_PASSWORD:-}
    if [ -z "$password" ] && [ -s "$password_file" ]; then
      password=$(cat "$password_file")
    fi
    if [ ! -f "$keystore" ]; then
      if [ -z "$password" ]; then
        password=$(od -An -tx1 -N 24 /dev/urandom | tr -d ' \n')
        (umask 077; printf '%s\n' "$password" > "$password_file")
      fi
      (umask 077; FLYME_PICKER_KEY_PASSWORD=$password keytool -genkeypair -keystore "$keystore" \
        -storetype PKCS12 -storepass:env FLYME_PICKER_KEY_PASSWORD -alias probe \
        -keyalg RSA -keysize 2048 -validity 3650 -dname 'CN=Flyme Photo Picker Albums')
    fi
    if [ -z "$password" ]; then
      echo 'Set FLYME_PICKER_KEY_PASSWORD for the existing local keystore.' >&2
      exit 1
    fi
    FLYME_PICKER_KEY_PASSWORD=$password java -cp "$build:$deps/apksig.jar" SignApk "$keystore" \
      "$build/manifest.apk" "$build/dex.zip" "$build/flymepicker.pending.apk"
    mv -f "$build/flymepicker.pending.apk" "$build/flymepicker.apk"
    ;;
  *)
    echo 'Usage: build.sh {compile|package}' >&2
    exit 2
    ;;
esac
