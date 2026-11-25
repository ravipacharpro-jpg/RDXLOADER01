#!/usr/bin/env bash
set -e
chmod +x ./gradlew
echo "Building FeaturesticBox Release AAR..."
./gradlew :FeaturesticBox:assembleRelease --no-daemon --stacktrace
echo "Done! AAR location: FeaturesticBox-source/build/outputs/aar/FeaturesticBox-release.aar"
