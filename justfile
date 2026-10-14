# List available recipes.
default:
    @just --list

# Everything CI runs: Kotlin warnings-as-errors, Android Lint, detekt (+ ktlint, Compose rules), unit tests.
check-all:
    ./gradlew check

# Optimised release APK (R8 + resource shrinking). Signed when RELEASE_KEYSTORE* env vars are set.
release-apk *GRADLE_ARGS:
    ./gradlew :app:assembleRelease {{GRADLE_ARGS}}
