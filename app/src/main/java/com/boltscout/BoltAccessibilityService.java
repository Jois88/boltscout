package com.boltscout;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.PixelFormat;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Monitors the Bolt Driver app for ride request screens.
 *
 * Detection strategy
 * ──────────────────
 * 1.  Gate: screen must contain "zł" AND ("Accept" OR "Przyjmij") AND an ETA
 *     pattern (\d+ min…) all at once — confirming an active ride request popup.
 * 2.  Collect every text node from the window tree in DFS order.
 * 3.  Find nodes whose text starts with \d+ min (e.g. "3 min • 2 km").
 * 4.  Pickup  = first valid text node after the 1st ETA marker.
 *     Dropoff = first valid text node after the 2nd ETA marker.
 * 5.  A node is invalid if it is shorter than 5 chars, purely numeric, or
 *     contains "km".
 * 6.  Deduplicates rapid-fire events (30 s cooldown per unique pair).
 * 7.  Shows a WindowManager TYPE_ACCESSIBILITY_OVERLAY floating over Bolt.
 *     The overlay auto-removes after 15 s or on tap.
 */
public class BoltAccessibilityService extends AccessibilityService {

    // Static reference for debug access from MainActivity
    public static BoltAccessibilityService instance;

    // Known Bolt driver app packages
    private static final String[] BOLT_PACKAGES = {
        "ee.mtakso.driver",
        "com.bolt.driver",
        "taxi.android.client"
    };

    // ETA node: "3 min", "12 min", "3 min • 2 km", etc.
    private static final Pattern ETA_PATTERN = Pattern.compile("^\\d+\\s+min");

    // Deduplication state
    private String lastPickup  = "";
    private String lastDropoff = "";
    private long   lastSentMs  = 0;
    private static final long COOLDOWN_MS = 30_000;

    // Single main-thread handler for both debounce and overlay auto-remove
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pendingProcess = null;
    private static final long DEBOUNCE_MS = 200;

    // Overlay state
    private WindowManager windowManager;
    private View overlayView;
    private final Runnable autoRemoveRunnable = this::removeOverlay;

    // ── Service lifecycle ─────────────────────────────────────────────────────

    @Override
    protected void onServiceConnected() {
        instance = this;
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes =
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED |
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags =
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS |
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS |
            AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        info.notificationTimeout = DEBOUNCE_MS;
        info.packageNames = BOLT_PACKAGES;
        setServiceInfo(info);

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        CharSequence pkg = event.getPackageName();
        if (pkg == null || !isBoltPackage(pkg.toString())) return;

        // Debounce: cancel any pending scan and schedule a new one
        if (pendingProcess != null) handler.removeCallbacks(pendingProcess);
        pendingProcess = () -> {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                processTree(root);
                root.recycle();
            }
        };
        handler.postDelayed(pendingProcess, DEBOUNCE_MS);
    }

    @Override
    public void onInterrupt() {
        if (pendingProcess != null) handler.removeCallbacks(pendingProcess);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        removeOverlay();
    }

    // ── Tree processing ────────────────────────────────────────────────────────

    private void processTree(AccessibilityNodeInfo root) {
        // Collect all text values in DFS traversal order
        List<String> textNodes = new ArrayList<>();
        collectTextNodes(root, textNodes);

        // Gate: require "zł" AND ("Accept" OR "Przyjmij") AND ETA pattern —
        // all three must be present simultaneously to confirm a ride request.
        boolean hasZl = false, hasButton = false, hasEta = false;
        for (String t : textNodes) {
            if (t.contains("zł"))                                hasZl    = true;
            if (t.contains("Accept") || t.contains("Przyjmij")) hasButton = true;
            if (ETA_PATTERN.matcher(t).find())                   hasEta   = true;
            if (hasZl && hasButton && hasEta) break;
        }
        if (!hasZl || !hasButton || !hasEta) {
            // Accept/Przyjmij gone means the ride request popup closed — dismiss overlay
            if (!hasButton && overlayView != null) removeOverlay();
            return;
        }

        // Log full screen text dump to file
        writeLog(textNodes);

        // Best-effort address extraction — empty string if pattern not found.
        // ETA nodes look like "3 min • 2 km"; the address is the next valid node.
        String pickup  = "";
        String dropoff = "";
        int etaCount = 0;

        for (int i = 0; i < textNodes.size(); i++) {
            if (ETA_PATTERN.matcher(textNodes.get(i)).find()) {
                etaCount++;
                for (int j = i + 1; j < textNodes.size(); j++) {
                    String candidate = textNodes.get(j);
                    if (isValidAddressNode(candidate)) {
                        if (etaCount == 1 && pickup.isEmpty())       pickup  = candidate;
                        else if (etaCount == 2 && dropoff.isEmpty()) dropoff = candidate;
                        break;
                    }
                }
                if (!dropoff.isEmpty()) break;
            }
        }

        // Deduplication: suppress if same pair was just shown within cooldown
        long now = System.currentTimeMillis();
        if (pickup.equals(lastPickup) && dropoff.equals(lastDropoff)
                && now - lastSentMs < COOLDOWN_MS) {
            return;
        }
        lastPickup  = pickup;
        lastDropoff = dropoff;
        lastSentMs  = now;

        showOverlay(pickup, dropoff);
    }

    // ── WindowManager overlay ──────────────────────────────────────────────────

    void showOverlay(final String pickup, final String dropoff) {
        removeOverlay();

        new Handler(Looper.getMainLooper()).post(() -> {
            overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_ride, null);

            TextView tvPickup  = overlayView.findViewById(R.id.tv_pickup_address);
            TextView tvDropoff = overlayView.findViewById(R.id.tv_dropoff_address);
            View     btnMaps   = overlayView.findViewById(R.id.btn_open_maps);

            tvPickup.setText(pickup.isEmpty()  ? "—" : pickup);
            tvDropoff.setText(dropoff.isEmpty() ? "—" : dropoff);

            btnMaps.setOnClickListener(v -> {
                removeOverlay();
                MapsIntentHelper.openDirections(BoltAccessibilityService.this, pickup, dropoff);
            });

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE   |
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.TOP;

            overlayView.setOnClickListener(v -> removeOverlay());
            windowManager.addView(overlayView, params);
            MapsIntentHelper.openDirections(BoltAccessibilityService.this, pickup, dropoff);
        });

        handler.postDelayed(autoRemoveRunnable, 15_000);
    }

    private void removeOverlay() {
        handler.removeCallbacks(autoRemoveRunnable);
        if (overlayView != null && windowManager != null) {
            try { windowManager.removeView(overlayView); } catch (Exception ignored) {}
            overlayView = null;
        }
    }

    // ── Flat text-node collection ──────────────────────────────────────────────

    private void collectTextNodes(AccessibilityNodeInfo node, List<String> out) {
        if (node == null) return;
        CharSequence txt = node.getText();
        if (txt != null) {
            String s = txt.toString().trim();
            if (!s.isEmpty()) out.add(s);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectTextNodes(child, out);
                child.recycle();
            }
        }
    }

    // ── Address node validation ────────────────────────────────────────────────

    private boolean isValidAddressNode(String s) {
        if (s == null || s.length() < 5) return false;
        if (s.matches("^\\d+$")) return false;
        if (s.contains("km")) return false;
        return true;
    }

    // ── File logging ───────────────────────────────────────────────────────────

    private void writeLog(List<String> textNodes) {
        try {
            java.io.File file = new java.io.File(
                Environment.getExternalStorageDirectory(), "bolt_scout_log.txt");
            String timestamp = new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
            StringBuilder sb = new StringBuilder();
            sb.append("\n=== ").append(timestamp).append(" ===\n");
            for (String t : textNodes) sb.append("  [").append(t).append("]\n");
            FileWriter fw = new FileWriter(file, true);
            fw.write(sb.toString());
            fw.close();
        } catch (IOException ignored) {}
    }

    // ── Utilities ──────────────────────────────────────────────────────────────

    private boolean isBoltPackage(String pkg) {
        for (String p : BOLT_PACKAGES) if (p.equals(pkg)) return true;
        return false;
    }
}
