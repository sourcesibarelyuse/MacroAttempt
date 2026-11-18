Write M9: Android adapters + Gradle scaffolding for the macro engine. Output multiple files clearly separated by headers like "// FILE: path/to/file.kt". Use ONLY Android SDK APIs (android.*, androidx.*). Assume pure-logic types exist in com.macroengine.core/dsl/detection packages (import them, don't redefine).

Generate these files:

// FILE: settings.gradle.kts
Standard Gradle Kotlin DSL settings with google() and mavenCentral() repos, rootProject.name = "MacroEngine", include(":app").

// FILE: build.gradle.kts (root)
plugins block with com.android.application 8.2.0 apply false, org.jetbrains.kotlin.android 1.9.20 apply false.

// FILE: app/build.gradle.kts
android { namespace = "com.macroengine.app", compileSdk = 34, defaultConfig { applicationId = "com.macroengine.app", minSdk = 24, targetSdk = 34, versionCode = 1, versionName = "0.1.0" }, compileOptions { sourceCompatibility = JavaVersion.VERSION_17, targetCompatibility = JavaVersion.VERSION_17 }, kotlinOptions { jvmTarget = "17" } }
dependencies: implementation(project(":core")) [if core is separate module, else just local paths], androidx.core:core-ktx:1.12.0, androidx.appcompat:appcompat:1.6.1, com.google.android.material:material:1.11.0, org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3, implementation("com.google.mlkit:text-recognition:16.0.0") [bundled ML Kit for OCR].

// FILE: app/src/main/AndroidManifest.xml
Manifest with package com.macroengine.app. <application> with android:name=".MacroApp" (Application subclass). Declare <service android:name=".MacroAccessibilityService" android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE" android:exported="true"> with <intent-filter><action android:name="android.accessibilityservice.AccessibilityService" /></intent-filter> and <meta-data android:name="android.accessibilityservice" android:resource="@xml/accessibility_config" />. Permissions: FOREGROUND_SERVICE, SYSTEM_ALERT_WINDOW (for overlay), POST_NOTIFICATIONS.

// FILE: app/src/main/res/xml/accessibility_config.xml
<accessibility-service xmlns:android="..." android:description="@string/accessibility_description" android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged" android:accessibilityFeedbackType="feedbackGeneric" android:canRetrieveWindowContent="true" android:canPerformGestures="true" android:notificationTimeout="100" android:accessibilityFlags="flagRetrieveInteractiveWindows" />

// FILE: app/src/main/res/values/strings.xml
<string name="app_name">MacroEngine</string> and <string name="accessibility_description">Enables gesture automation for macro playback.</string>

// FILE: app/src/main/kotlin/com/macroengine/app/MacroApp.kt
class MacroApp : Application() with companion object { lateinit var instance: MacroApp private set }. onCreate initializes a singleton EngineCoordinator (your main orchestrator class, define it here or reference it).

// FILE: app/src/main/kotlin/com/macroengine/app/MacroAccessibilityService.kt
class MacroAccessibilityService : AccessibilityService() with companion object { var instance: MacroAccessibilityService? = null private set }. onServiceConnected sets instance. onDestroy clears it. Expose fun dispatchGesture(plan: GesturePlan, displayId: Int?): InjectionResult using the M2 NativeGestureInjector logic (build GestureDescription from plan.strokes via Path, call dispatchGesture with a GestureResultCallback that resumes a suspend function via suspendCancellableCoroutine). Include a simple fun runScript(ir: FlowIr): RunResult that compiles IR via FlowCompiler and runs it through a RunLane wired to this service as ActionPort (invoke maps to dispatchGesture), a StatePort stub (query returns false for now), and System.currentTimeMillis() as clock.

// FILE: app/src/main/kotlin/com/macroengine/app/EngineCoordinator.kt
class EngineCoordinator(context: Context) holding references to the service (via MacroAccessibilityService.instance), a MediaProjection manager stub (just declare the field, note it needs user consent via Activity), and methods: fun isReady(): Boolean (service connected), fun loadScript(dsl: String): FlowIr (parse via Parser), fun execute(ir: FlowIr): RunResult (delegate to service.runScript).

// FILE: app/src/main/kotlin/com/macroengine/app/MainActivity.kt
Simple AppCompatActivity with a button that, on click, calls EngineCoordinator.loadScript + execute with a hardcoded test DSL string (e.g. "on start: log(\"hello\")"), shows result via Toast. Request overlay permission if needed.

// FILE: .github/workflows/build.yml (GitHub Actions for M10)
name: Build APK. on: [push, pull_request]. jobs: build: runs-on: ubuntu-latest. steps: checkout, setup-java v17, setup gradle (optional, use ./gradlew), run ./gradlew assembleDebug, upload-artifact app-debug.apk.

Output ONLY code. No explanations outside comments.
