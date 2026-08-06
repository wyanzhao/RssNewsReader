#!/usr/bin/env bash
# Unified validation entry: runs the Python offline test suite and the
# Android unit tests in sequence and reports a combined pass/fail summary.
#
# Usage:
#   scripts/check_all.sh                 # run both suites
#   scripts/check_all.sh --python-only   # skip the Android suite
#   scripts/check_all.sh --android-only  # skip the Python suite

set -u

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

RUN_PYTHON=1
RUN_ANDROID=1
case "${1:-}" in
  --python-only) RUN_ANDROID=0 ;;
  --android-only) RUN_PYTHON=0 ;;
  "") ;;
  *)
    echo "unknown option: ${1}" >&2
    echo "usage: scripts/check_all.sh [--python-only|--android-only]" >&2
    exit 2
    ;;
esac

PYTHON_STATUS=SKIPPED
ANDROID_STATUS=SKIPPED

if [ "$RUN_PYTHON" -eq 1 ]; then
  echo "== Python offline tests =="
  if (cd "$REPO_ROOT" && python3 -m unittest discover -s "$REPO_ROOT/tests" -p 'test_*.py'); then
    PYTHON_STATUS=PASS
  else
    PYTHON_STATUS=FAIL
  fi
  echo
fi

if [ "$RUN_ANDROID" -eq 1 ]; then
  echo "== Android unit tests =="
  # Resolve a JDK when JAVA_HOME is unset and the shell has no java on PATH
  # (the macOS /usr/bin/java stub fails without a registered JVM).
  if [ -z "${JAVA_HOME:-}" ] && ! java -version >/dev/null 2>&1; then
    if [ -x /usr/libexec/java_home ]; then
      JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null || true)"
    fi
    if [ -z "${JAVA_HOME:-}" ]; then
      for cand in /opt/homebrew/opt/openjdk@*/libexec/openjdk.jdk/Contents/Home /opt/homebrew/opt/openjdk@* /usr/local/opt/openjdk@*; do
        if [ -x "$cand/bin/java" ]; then
          JAVA_HOME="$cand"
          break
        fi
      done
    fi
    if [ -z "${JAVA_HOME:-}" ]; then
      echo "no JDK found; set JAVA_HOME to run Android tests" >&2
      ANDROID_STATUS=FAIL
    else
      export JAVA_HOME
      echo "using JAVA_HOME=$JAVA_HOME"
    fi
  fi
  if [ "$ANDROID_STATUS" = SKIPPED ]; then
    if [ -x "$REPO_ROOT/android/gradlew" ]; then
      if (cd "$REPO_ROOT/android" && ./gradlew test); then
        ANDROID_STATUS=PASS
      else
        ANDROID_STATUS=FAIL
      fi
    else
      echo "android/gradlew not found or not executable" >&2
      ANDROID_STATUS=FAIL
    fi
  fi
  echo
fi

echo "== Summary =="
echo "Python offline tests: $PYTHON_STATUS"
echo "Android unit tests:   $ANDROID_STATUS"

if [ "$PYTHON_STATUS" = FAIL ] || [ "$ANDROID_STATUS" = FAIL ]; then
  exit 1
fi
exit 0
