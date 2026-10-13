# List available recipes.
default:
    @just --list

# Everything CI runs: Kotlin warnings-as-errors, Android Lint, detekt (+ ktlint, Compose rules), unit tests.
check-all:
    ./gradlew check
