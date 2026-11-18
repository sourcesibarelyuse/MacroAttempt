# MacroEngine

A pure-Kotlin macro automation engine for Android.

## Architecture
- **M1–M8**: Pure Kotlin logic (Geometry, Adapters, Persistence, Ticker, Hardening, DSL, Kernel, Detection).
- **M9**: Android App, Gradle build, and Accessibility Service wiring.
- **M10**: GitHub Actions CI/CD for APK generation.

## Building
Run `gradle assembleDebug` in the `macro-engine/` directory, or use the provided GitHub Actions workflow.
