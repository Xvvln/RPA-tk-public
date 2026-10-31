#!/bin/zsh
set -euo pipefail

SCRIPT_DIR=${0:A:h}
PROJECT_JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}
ADB_BIN=${ADB_BIN:-$(command -v adb)}
SERVICE_COMPONENT="com.xwell.rpatt/com.xwell.rpatt.automation.WarmupAccessibilityService"
APK_PATH="${SCRIPT_DIR}/app/build/outputs/apk/debug/app-debug.apk"

before_services=$("${ADB_BIN}" shell settings get secure enabled_accessibility_services)

(
    cd "${SCRIPT_DIR}"
    JAVA_HOME="${PROJECT_JAVA_HOME}" ./gradlew assembleDebug
)
"${ADB_BIN}" install -r "${APK_PATH}"
"${ADB_BIN}" shell am start -n com.xwell.rpatt/.MainActivity

after_services=$("${ADB_BIN}" shell settings get secure enabled_accessibility_services)
if [[ "${before_services}" == *"${SERVICE_COMPONENT}"* \
        && "${after_services}" != *"${SERVICE_COMPONENT}"* ]]; then
    print -u2 "错误：覆盖安装后无障碍授权丢失；未执行后续强停操作。"
    exit 2
fi

print "Debug APK 已覆盖安装并打开；未执行 force-stop，无障碍授权状态保持不变。"
