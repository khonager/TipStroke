#!/bin/sh
# Xcode provides CONFIGURATION, SDK_NAME, ARCHS and its build directories.
set -eu
cd "$(dirname "$0")/.."
if [ "${OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED:-}" = YES ]; then exit 0; fi
if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 17)
    export JAVA_HOME
fi
./gradlew -Ptipstroke.appleOnly=true :shared-ui:embedAndSignAppleFrameworkForXcode
