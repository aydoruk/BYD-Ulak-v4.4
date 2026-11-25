#!/usr/bin/env bash
set -e
echo "===================================================================="
echo "  ULAK v4.0 - BYD Seal U DM-i (DiLink 3.0) & S26 Ultra APK Derleyici"
echo "===================================================================="
if [ -f "./gradlew" ]; then
    chmod +x ./gradlew
    ./gradlew testDebugUnitTest assembleDebug
elif command -v gradle >/dev/null 2>&1; then
    gradle testDebugUnitTest assembleDebug
else
    echo "[!] HATA: gradle bulunamadı. Lütfen projeyi Android Studio ile açın veya GitHub Actions kullanın."
    exit 1
fi
echo ""
echo "[OK] Testler geçti ve APK dosyaları hazır:"
echo " 1) Araç APK:  app-car/build/outputs/apk/debug/UlakCar_DiLink3_v4.0.0-DiLink3-debug.apk"
echo " 2) Mobil APK: app-mobile/build/outputs/apk/debug/UlakMobile_S26Ultra_v4.0.0-S26Ultra-debug.apk"
