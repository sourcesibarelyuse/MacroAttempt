Write M11: The Floating Overlay Service for MacroEngine. ONE pure Kotlin file (plus necessary XML if needed, but prefer programmatic Views for simplicity), package com.macroengine.app.
Goal: Replicate the "Haua Auto Clicker" floating button experience.
Requirements:
1. Class `FloatingOverlayService : Service()`.
2. It must use `WindowManager` to add a view with `LayoutParams.TYPE_APPLICATION_OVERLAY`.
3. The View should be a small, circular FloatingActionButton (use a simple colored CircleDrawable or standard Android FAB if available, but programmatic is safer).
4. **Drag Logic:** The button must be draggable. Implement `OnTouchListener` that updates `LayoutParams.x` and `y` based on `MotionEvent.getRawX/Y`.
5. **Click Logic:** A short tap (without dragging) should toggle a small popup menu.
6. **The Menu:** A small vertical LinearLayout that appears next to the button with 3 buttons:
    - "Play" (Green): Calls `MacroApp.instance.coordinator.execute(currentScript)` in a background thread.
    - "Pause" (Yellow): Stops execution.
    - "Add Point" (Blue): Toggles a "Recording Mode" (just a boolean flag for now, we will wire M12 next).
7. **Permissions:** Ensure it checks `Settings.canDrawOverlays` before starting (though the manifest already has it).
8. **Lifecycle:** `onStartCommand` returns `START_STICKY`. `onDestroy` removes the view.
9. **Integration:** It must import and use `com.macroengine.app.MacroApp` to access the coordinator.
10. **Output:** ONLY Kotlin code. No explanations outside comments.
