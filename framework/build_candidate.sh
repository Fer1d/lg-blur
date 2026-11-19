#!/bin/bash
set -euo pipefail
R=$(dirname "$(readlink -f "$0")")
V=${1:?supply unique version, e.g. v3}
[[ "$V" =~ ^v[0-9]+$ ]]
ANDROID=${ANDROID_JAR:?set ANDROID_JAR to an external Android platform jar}
APK=${APKTOOL_JAR:?set APKTOOL_JAR to an external apktool jar}
SDK=${ANDROID_BUILD_TOOLS:?set ANDROID_BUILD_TOOLS to external build-tools directory}
CP="$APK:$R/host-classes"
# Explicitly refuse overwriting frozen candidates. No JAR, adb, ART or deployment.
[[ ! -e "$R/CANDIDATE-LIFECYCLE-$V-classes3.dex" ]]
python3 -I "$R/generate_lifecycle.py" "$R" "lifecycle-smali-$V"
javac --release 8 -Xlint:-options -cp "$ANDROID:$R/stub-classes" -d "$R/helper-classes" "$R/LandscapeSizing.java" "$R/ValidatedTargets.java" "$R/LandscapeLifecycle.java"
mkdir "$R/helper-dex-$V"
"$SDK/d8" --min-api 29 --lib "$ANDROID" --classpath "$R/stub-classes" --output "$R/helper-dex-$V" "$R"/helper-classes/com/lge/blurengine2/*.class
java -cp "$CP" AssemblyTool decode "$R/helper-dex-$V/classes.dex" "$R/helper-decoded-$V"
python3 -I -c 'import pathlib,sys;r=pathlib.Path(sys.argv[1]);v=sys.argv[2];[(r/("lifecycle-smali-"+v)/("landscape_"+p.name)).write_bytes(p.read_bytes()) for p in (r/("helper-decoded-"+v)).glob("*.smali")]' "$R" "$V"
java -Xmx3g -cp "$CP" AssemblyTool build "$R/lifecycle-smali-$V" "$R/CANDIDATE-LIFECYCLE-$V-classes3.dex"
java -cp "$CP" CandidateDexCheck "$R/input/classes3.dex" "$R/CANDIDATE-LIFECYCLE-$V-classes3.dex" | tee "$R/static-$V.log"
"$SDK/dexdump" -c "$R/CANDIDATE-LIFECYCLE-$V-classes3.dex" > "$R/dexdump-$V.log" 2>&1
