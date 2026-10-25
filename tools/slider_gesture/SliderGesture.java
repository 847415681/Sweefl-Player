package com.sweefl.tools;

import android.app.UiAutomation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;

/** Standalone root app_process test helper. No app code or accessibility settings are persisted. */
public final class SliderGesture {
    private static final String PACKAGE = "com.sweefl.player";
    private static final String LABEL = "播放进度";
    private UiAutomation automation;
    private long downTime;
    private float pointerX;
    private float pointerY;
    private boolean pointerDown;

    private static final class State {
        final Rect bounds;
        final float min, max, current;
        final long elapsed = SystemClock.elapsedRealtime();
        State(Rect bounds, AccessibilityNodeInfo.RangeInfo range) {
            this.bounds = bounds; min = range.getMin(); max = range.getMax(); current = range.getCurrent();
        }
        float fraction() { return (current - min) / (max - min); }
        JSONObject json() throws Exception {
            return new JSONObject().put("bounds", new JSONArray(new int[]{bounds.left, bounds.top, bounds.right, bounds.bottom}))
                .put("min", min).put("max", max).put("current", current).put("fraction", fraction())
                .put("elapsedRealtimeMs", elapsed);
        }
    }

    private State find(AccessibilityNodeInfo node) {
        if (PACKAGE.contentEquals(node.getPackageName() == null ? "" : node.getPackageName()) &&
            LABEL.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription())) {
            node.refresh();
            AccessibilityNodeInfo.RangeInfo range = node.getRangeInfo();
            Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
            if (range != null && range.getMax() > range.getMin() && bounds.width() > 0 && node.isEnabled())
                return new State(bounds, range);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { State found = find(child); if (found != null) return found; }
            finally { child.recycle(); }
        }
        return null;
    }

    private State read() throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root != null) {
                try { State state = find(root); if (state != null) return state; }
                finally { root.recycle(); }
            }
            SystemClock.sleep(100);
        }
        throw new IllegalStateException("No enabled Sweefl slider with real accessibility RangeInfo");
    }

    private void inject(int action, float x, float y) {
        MotionEvent.PointerProperties property = new MotionEvent.PointerProperties();
        property.id = 0; property.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords coordinates = new MotionEvent.PointerCoords();
        coordinates.x = x; coordinates.y = y;
        coordinates.pressure = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL ? 0 : 1;
        coordinates.size = 1;
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1,
            new MotionEvent.PointerProperties[]{property}, new MotionEvent.PointerCoords[]{coordinates},
            0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            if (!automation.injectInputEvent(event, true)) throw new IllegalStateException("Input injection rejected");
            pointerX = x; pointerY = y;
        } finally { event.recycle(); }
    }

    private JSONArray scrubLabels(AccessibilityNodeInfo node) {
        JSONArray labels = new JSONArray();
        collectLabels(node, labels);
        return labels;
    }
    private void collectLabels(AccessibilityNodeInfo node, JSONArray labels) {
        CharSequence text = node.getText();
        if (text != null && text.toString().matches("[−-]?\\d{1,2}:\\d{2}(:\\d{2})?")) labels.put(text.toString());
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) try { collectLabels(child, labels); } finally { child.recycle(); }
        }
    }

    private void gesture(Context context, double target, long duration, String screenshot, JSONObject report) throws Exception {
        State initial = read();
        float density = context.getResources().getDisplayMetrics().density;
        int slop = ViewConfiguration.get(context).getScaledTouchSlop();
        // Material3 adds 10dp semantics padding; GlassSlider's custom thumb is 20dp wide.
        float left = initial.bounds.left + 20 * density;
        float right = initial.bounds.right - 20 * density;
        float width = right - left;
        if (width <= 0) throw new IllegalStateException("Invalid slider geometry");
        pointerX = left + width * initial.fraction();
        pointerY = initial.bounds.exactCenterY();
        float targetX = left + width * (float) target;
        float startX = pointerX;
        double tolerance = 1.5 / width;
        report.put("initial", initial.json()).put("density", density).put("scaledTouchSlopPx", slop)
            .put("thumbCenterMinPx", left).put("thumbCenterMaxPx", right)
            .put("targetFraction", target).put("selectionToleranceFraction", tolerance)
            .put("requestedMoveDurationMs", duration).put("pointerId", 0);
        downTime = SystemClock.uptimeMillis();
        report.put("downTimeUptimeMs", downTime).put("downElapsedRealtimeMs", SystemClock.elapsedRealtime());
        pointerDown = true;
        inject(MotionEvent.ACTION_DOWN, pointerX, pointerY);
        int steps = Math.max(2, (int)(duration / 40));
        for (int step = 1; step <= steps; step++) {
            long due = downTime + duration * step / steps;
            long wait = due - SystemClock.uptimeMillis();
            if (wait > 0) SystemClock.sleep(wait);
            inject(MotionEvent.ACTION_MOVE, startX + (targetX - startX) * step / steps, pointerY);
        }
        SystemClock.sleep(200);
        JSONArray samples = new JSONArray(); report.put("heldSamples", samples);
        State held = read(); samples.put(held.json().put("pointerX", pointerX));
        for (int attempt = 0; Math.abs(held.fraction() - target) > tolerance && attempt < 6; attempt++) {
            // Slop has already been consumed. Correct from the measured value, not a guessed endpoint.
            float correction = (float)(target - held.fraction()) * width;
            float correctedX = Math.max(left, Math.min(right, pointerX + correction));
            if (Math.abs(correctedX - pointerX) < .25f) throw new IllegalStateException("No correction travel remains");
            inject(MotionEvent.ACTION_MOVE, correctedX, pointerY);
            SystemClock.sleep(180);
            held = read(); samples.put(held.json().put("pointerX", pointerX));
        }
        if (Math.abs(held.fraction() - target) > tolerance)
            throw new IllegalStateException("Held slider value did not reach requested fraction");
        report.put("preUp", held.json()).put("selectionVerified", true);
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        if (root != null) try { report.put("heldTimeLabels", scrubLabels(root)); } finally { root.recycle(); }
        Bitmap bitmap = automation.takeScreenshot();
        if (bitmap == null) throw new IllegalStateException("No held screenshot available");
        try (FileOutputStream stream = new FileOutputStream(screenshot)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IllegalStateException("Screenshot encoding failed");
        } finally { bitmap.recycle(); }
        // Recheck after the screenshot: only release a positively measured selection.
        held = read(); report.put("preUp", held.json());
        if (Math.abs(held.fraction() - target) > tolerance) throw new IllegalStateException("Held selection changed before UP");
        report.put("upElapsedRealtimeMs", SystemClock.elapsedRealtime()).put("upX", pointerX).put("upY", pointerY);
        inject(MotionEvent.ACTION_UP, pointerX, pointerY); pointerDown = false;
        report.put("ok", true);
    }

    public static void main(String[] args) {
        JSONObject report = new JSONObject();
        SliderGesture driver = new SliderGesture();
        HandlerThread thread = new HandlerThread("SweeflSliderAutomation");
        boolean connected = false;
        int exit = 1;
        try {
            if (args.length != 3) throw new IllegalArgumentException("fraction durationMs screenshotPath required");
            double fraction = Double.parseDouble(args[0]); long duration = Long.parseLong(args[1]);
            if (!(fraction > 0 && fraction < 1) || duration < 100 || duration > 120000 ||
                !args[2].matches("/data/local/tmp/sweefl-slider-[a-f0-9]+\\.png"))
                throw new IllegalArgumentException("Invalid bounded gesture arguments");
            Looper.prepareMainLooper();
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object systemThread = activityThread.getMethod("systemMain").invoke(null);
            Context context = (Context)activityThread.getMethod("getSystemContext").invoke(systemThread);
            thread.start();
            Class<?> connectionInterface = Class.forName("android.app.IUiAutomationConnection");
            Object connection = Class.forName("android.app.UiAutomationConnection").getDeclaredConstructor().newInstance();
            Constructor<UiAutomation> constructor = UiAutomation.class.getDeclaredConstructor(Looper.class, connectionInterface);
            constructor.setAccessible(true);
            driver.automation = constructor.newInstance(thread.getLooper(), connection);
            UiAutomation.class.getMethod("connect", int.class).invoke(driver.automation, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            connected = true;
            driver.gesture(context, fraction, duration, args[2], report);
            exit = 0;
        } catch (Throwable failure) {
            try { report.put("ok", false).put("errorType", failure.getClass().getSimpleName())
                .put("error", String.valueOf(failure.getMessage()).replaceAll("https?://\\S+", "[redacted]")); }
            catch (Exception ignored) { }
        } finally {
            if (driver.pointerDown && driver.automation != null) {
                try { driver.inject(MotionEvent.ACTION_CANCEL, driver.pointerX, driver.pointerY); }
                catch (Throwable ignored) { }
            }
            if (connected) try { UiAutomation.class.getMethod("disconnect").invoke(driver.automation); }
                catch (Throwable ignored) { }
            thread.quitSafely();
            System.out.println("SWEEFL_SLIDER_RESULT=" + report.toString());
            System.exit(exit);
        }
    }
}
