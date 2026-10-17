#!/bin/bash
# Builds the APK without the Android SDK: aapt2 + ProGuard + dx + apksigner.
set -euo pipefail
cd "$(dirname "$0")"; ROOT=$(pwd)
ANDROID_JAR=${ANDROID_JAR:-/home/claude/dl/android-all-15-robolectric-12650502.jar}
OUT=build/apk; rm -rf $OUT; mkdir -p $OUT/res
aapt2 compile --dir app/res -o $OUT/res.zip
aapt2 link -o $OUT/base.apk -I "${FRAMEWORK_RES:-/home/claude/dl/fw34.zip}" --manifest app/AndroidManifest.xml -A app/assets $OUT/res.zip --auto-add-overlay
STDLIB=$(ls build/libs-rt/kotlin-stdlib-*.jar)
ZXING=$(ls build/libs-rt/core-*.jar)
cat > $OUT/rules.pro <<RULES
-injars $ROOT/build/classes/kotlin/main
-injars $ROOT/$STDLIB(!META-INF/versions/**,!module-info.class)
-injars $ROOT/$ZXING(!META-INF/**)
-libraryjars $ANDROID_JAR
-libraryjars /usr/lib/jvm/java-21-openjdk-amd64/jmods/java.base.jmod(!**.jar;!module-info.class)
-outjars $ROOT/$OUT/app.jar
-keep class com.yaya.praline.** { *; }
-dontobfuscate
-dontoptimize

-dontnote **
-ignorewarnings
RULES
java -cp "build/pg-libs/*" proguard.ProGuard @$OUT/rules.pro > $OUT/proguard.log 2>&1 || { tail -30 $OUT/proguard.log; exit 1; }
mkdir -p $OUT/x && (cd $OUT/x && unzip -q ../app.jar)
if find $OUT/x -name '*.class' -print0 | xargs -0 -n 100 javap -c -p 2>/dev/null | grep -q invokedynamic; then
  echo "WARNING: invokedynamic left in:"; for f in $(find $OUT/x -name '*.class'); do javap -c -p "$f" | grep -q invokedynamic && echo "  $f"; done
fi
dalvik-exchange --dex --min-sdk-version=26 --output=$OUT/classes.dex $OUT/app.jar
cp $OUT/base.apk $OUT/unsigned.apk
(cd $OUT && zip -q -j unsigned.apk classes.dex)
zipalign -f -p 4 $OUT/unsigned.apk $OUT/aligned.apk
apksigner sign --ks signing/release.jks --ks-pass file:signing/password.txt --key-pass file:signing/password.txt --out $OUT/Praline.apk $OUT/aligned.apk
apksigner verify --print-certs $OUT/Praline.apk | head -3
ls -la $OUT/Praline.apk
