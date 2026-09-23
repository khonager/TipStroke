#!/usr/bin/env sh
set -eu

if [ -e /etc/NIXOS ] && [ "${TIPSTROKE_NIX_SHELL:-}" != 1 ] && command -v nix >/dev/null 2>&1; then
  exec nix develop --command sh "$0" "$@"
fi

AVD_NAME=TipStroke_Tablet_API_35
# The Pixel Tablet hardware profile supplies the tablet form factor; TipStroke
# does not need Google Play services, so the smaller AOSP image is sufficient.
SYSTEM_IMAGE='system-images;android-35;default;x86_64'
EMULATOR_PORT=5562
EMULATOR_SERIAL=emulator-$EMULATOR_PORT

sdk_root() {
  if [ -n "${ANDROID_SDK_ROOT:-}" ]; then
    printf '%s\n' "$ANDROID_SDK_ROOT"
  elif [ -n "${ANDROID_HOME:-}" ]; then
    printf '%s\n' "$ANDROID_HOME"
  elif [ -f local.properties ]; then
    sed -n 's/^sdk\.dir=//p' local.properties | head -n 1
  else
    printf '%s\n' "$HOME/Android/Sdk"
  fi
}

find_sdk_tool() {
  tool_name=$1
  sdk=$2
  for candidate in \
    "$sdk/$tool_name/$tool_name" \
    "$sdk/platform-tools/$tool_name" \
    "$sdk/emulator/$tool_name" \
    "$sdk/cmdline-tools/latest/bin/$tool_name" \
    "$sdk/cmdline-tools/bin/$tool_name" \
    "$sdk"/cmdline-tools/*/bin/"$tool_name"
  do
    if [ -x "$candidate" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

SDK_ROOT=$(sdk_root)
export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"
SYSTEM_IMAGE_DIR="$SDK_ROOT/system-images/android-35/default/x86_64"
mkdir -p .gradle

setup_avd() {
  SDKMANAGER=$(find_sdk_tool sdkmanager "$SDK_ROOT") || {
    printf 'Android sdkmanager was not found under %s. Install Android command-line tools first.\n' "$SDK_ROOT" >&2
    exit 1
  }
  AVDMANAGER=$(find_sdk_tool avdmanager "$SDK_ROOT") || {
    printf 'Android avdmanager was not found under %s.\n' "$SDK_ROOT" >&2
    exit 1
  }

  printf 'Installing the Android Emulator and tablet system image...\n'
  yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" emulator platform-tools "$SYSTEM_IMAGE"

  avd_config="$HOME/.android/avd/$AVD_NAME.avd/config.ini"
  if [ -f "$avd_config" ]; then
    image_path=$(sed -n 's/^image\.sysdir\.1=//p' "$avd_config" | head -n 1)
    if [ -z "$image_path" ] || { [ ! -d "$SDK_ROOT/$image_path" ] && [ ! -d "$(dirname "$SDK_ROOT")/$image_path" ]; }; then
      printf 'Recreating %s with the corrected SDK root...\n' "$AVD_NAME"
      "$AVDMANAGER" delete avd --name "$AVD_NAME"
    fi
  fi
  if ! "$AVDMANAGER" list avd | grep -q "Name: $AVD_NAME"; then
    printf 'Creating %s...\n' "$AVD_NAME"
    printf 'no\n' | "$AVDMANAGER" create avd --force --name "$AVD_NAME" --package "$SYSTEM_IMAGE" --device pixel_tablet
  fi
  printf 'Laptop emulator ready. Run ./tipstroke laptop.\n'
}

run_app() {
  ADB=$(find_sdk_tool adb "$SDK_ROOT") || {
    printf 'adb was not found under %s. Run ./tipstroke laptop-setup first.\n' "$SDK_ROOT" >&2
    exit 1
  }
  EMULATOR=$(find_sdk_tool emulator "$SDK_ROOT") || {
    printf 'Android Emulator was not found under %s. Run ./tipstroke laptop-setup first.\n' "$SDK_ROOT" >&2
    exit 1
  }
  if ! "$EMULATOR" -list-avds | grep -qx "$AVD_NAME"; then
    printf 'AVD %s does not exist. Run ./tipstroke laptop-setup first.\n' "$AVD_NAME" >&2
    exit 1
  fi

  SERIAL=$("$ADB" devices | awk -v serial="$EMULATOR_SERIAL" '$1 == serial && $2 == "device" { print $1 }')
  if [ -z "$SERIAL" ]; then
    printf 'Starting %s...\n' "$AVD_NAME"
    nohup setsid -f "$EMULATOR" -avd "$AVD_NAME" -sysdir "$SYSTEM_IMAGE_DIR" -port "$EMULATOR_PORT" \
      -no-boot-anim -no-audio -gpu auto \
      </dev/null >.gradle/tipstroke-emulator.log 2>&1 &
    attempts=0
    while [ "$attempts" -lt 90 ]; do
      SERIAL=$("$ADB" devices | awk -v serial="$EMULATOR_SERIAL" '$1 == serial && ($2 == "device" || $2 == "offline") { print $1 }')
      [ -n "$SERIAL" ] && break
      attempts=$((attempts + 1))
      sleep 1
    done
  fi
  if [ -z "$SERIAL" ]; then
    printf 'The emulator did not start. See .gradle/tipstroke-emulator.log.\n' >&2
    exit 1
  fi

  "$ADB" -s "$SERIAL" wait-for-device
  RUNNING_AVD=$("$ADB" -s "$SERIAL" emu avd name 2>/dev/null | head -n 1 | tr -d '\r')
  if [ "$RUNNING_AVD" != "$AVD_NAME" ]; then
    printf '%s is occupied by AVD %s; close it and rerun.\n' "$SERIAL" "${RUNNING_AVD:-unknown}" >&2
    exit 1
  fi
  attempts=0
  while [ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != 1 ]; do
    attempts=$((attempts + 1))
    if [ "$attempts" -ge 120 ]; then
      printf 'Android did not finish booting. See .gradle/tipstroke-emulator.log.\n' >&2
      exit 1
    fi
    sleep 1
  done

  ./gradlew :app:assembleDebug
  "$ADB" -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
  "$ADB" -s "$SERIAL" shell am start -n dev.tipstroke.app/.MainActivity >/dev/null
  printf 'TipStroke is running on %s.\n' "$SERIAL"
}

case "${1:-run}" in
  setup) setup_avd ;;
  run) run_app ;;
  *) printf 'Usage: %s [setup|run]\n' "$0" >&2; exit 2 ;;
esac
