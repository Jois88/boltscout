package com.boltscout;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

/**
 * Setup / status screen for Bolt Scout.
 *
 * Guides the driver through two one-time permissions:
 *   1. Accessibility Service  — required to read Bolt's screen content.
 *   2. Draw Over Other Apps   — required so the verdict window floats on top
 *      of the Bolt driver app without needing to alt-tab.
 *
 * Once both are granted the screen shows a green "Scout is ACTIVE" status
 * and the driver can switch back to Bolt; everything runs in the background.
 */
public class MainActivity extends AppCompatActivity {

    private static final String BOLT_PACKAGE = "ee.mtakso.driver";

    // Debug test address pairs
    private static final String[][] FIRE_TESTS = {
        {"Ulica Wojska Polskiego 50, Warszawa 01-554",        "Ulica Siennicka 50, Warszawa 04-393"},
        {"Baseny Inflancka 02 - Bus Stop, Westfield Arkadia", "Ulica Wiatraczna 21, Warszawa 04-384"},
        {"Lotnisko Chopina, Warszawa",                         "Metro Bemowo, Bemowo, Warszawa"},
        {"Ulica Urocza 5, Warszawa 04-651",                   "Bollywood Lounge Restaurant, Bar & Sheesha Bar, Nowogrodzka, Warsaw"},
        {"Ulica Marszałkowska 100, Warszawa",                  "Plac Przymierza 4, Warszawa 03-944"},
    };

    // UI
    private View           statusDot;
    private TextView       tvServiceStatus;
    private MaterialButton btnEnableService;
    private View           overlayDot;
    private MaterialButton btnGrantOverlay;
    private MaterialButton btnOpenBolt;
    private MaterialButton btnFire1, btnFire2, btnFire3, btnFire4, btnFire5;

    // Refresh status every second while the activity is visible
    private final Handler    handler  = new Handler(Looper.getMainLooper());
    private final Runnable   ticker   = this::refreshStatus;

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        setupClickListeners();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        handler.postDelayed(ticker, 1000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
    }

    // ── Status refresh ─────────────────────────────────────────────────────────

    private void refreshStatus() {
        boolean serviceOn = isAccessibilityServiceEnabled();
        boolean overlayOn = Settings.canDrawOverlays(this);

        // Service status
        if (serviceOn) {
            statusDot.setBackgroundTintList(
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_active)));
            tvServiceStatus.setText(R.string.service_active);
            tvServiceStatus.setTextColor(
                ContextCompat.getColor(this, R.color.status_active));
            btnEnableService.setText("Enabled ✓");
            btnEnableService.setBackgroundTintList(
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.verdict_take_bg)));
        } else {
            statusDot.setBackgroundTintList(
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_inactive)));
            tvServiceStatus.setText(R.string.service_inactive);
            tvServiceStatus.setTextColor(
                ContextCompat.getColor(this, R.color.status_inactive));
            btnEnableService.setText(R.string.btn_enable_service);
            btnEnableService.setBackgroundTintList(
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent)));
        }

        // Overlay status
        if (overlayOn) {
            overlayDot.setBackgroundTintList(
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_active)));
            btnGrantOverlay.setText("Overlay granted ✓");
            btnGrantOverlay.setBackgroundTintList(
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.verdict_take_bg)));
        } else {
            overlayDot.setBackgroundTintList(
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_pending)));
            btnGrantOverlay.setText(R.string.btn_grant_overlay);
            btnGrantOverlay.setBackgroundTintList(
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent_dim)));
        }

        // Re-schedule
        handler.removeCallbacks(ticker);
        handler.postDelayed(ticker, 1000);
    }

    // ── Accessibility service check ────────────────────────────────────────────

    private boolean isAccessibilityServiceEnabled() {
        String flat = Settings.Secure.getString(
            getContentResolver(),
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(flat)) return false;

        String service = getPackageName() + "/"
                       + BoltAccessibilityService.class.getName();
        // TextUtils.SimpleStringSplitter splits on ':'
        android.text.TextUtils.SimpleStringSplitter colonSplitter =
            new android.text.TextUtils.SimpleStringSplitter(':');
        colonSplitter.setString(flat);
        while (colonSplitter.hasNext()) {
            if (colonSplitter.next().equalsIgnoreCase(service)) return true;
        }
        return false;
    }

    // ── Click listeners ────────────────────────────────────────────────────────

    private void setupClickListeners() {
        btnEnableService.setOnClickListener(v -> {
            // Open Android Accessibility Settings
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        });

        btnGrantOverlay.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, 1001);
            }
        });

        btnFire1.setOnClickListener(v -> fireTest(0));
        btnFire2.setOnClickListener(v -> fireTest(1));
        btnFire3.setOnClickListener(v -> fireTest(2));
        btnFire4.setOnClickListener(v -> fireTest(3));
        btnFire5.setOnClickListener(v -> fireTest(4));

        btnOpenBolt.setOnClickListener(v -> {
            // Try to open the Bolt driver app
            Intent launch = getPackageManager()
                .getLaunchIntentForPackage(BOLT_PACKAGE);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
            } else {
                // Bolt not installed — open Play Store
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=" + BOLT_PACKAGE)));
                } catch (Exception e) {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id="
                                  + BOLT_PACKAGE)));
                }
            }
        });
    }

    private void fireTest(int index) {
        BoltAccessibilityService svc = BoltAccessibilityService.instance;
        if (svc == null) {
            android.widget.Toast.makeText(this,
                "Enable Accessibility Service first", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        String[] pair = FIRE_TESTS[index];
        svc.showOverlay(pair[0], pair[1]);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        refreshStatus();
    }

    // ── View binding ───────────────────────────────────────────────────────────

    private void bindViews() {
        statusDot        = findViewById(R.id.status_dot);
        tvServiceStatus  = findViewById(R.id.tv_service_status);
        btnEnableService = findViewById(R.id.btn_enable_service);
        overlayDot       = findViewById(R.id.overlay_dot);
        btnGrantOverlay  = findViewById(R.id.btn_grant_overlay);
        btnOpenBolt      = findViewById(R.id.btn_open_bolt);
        btnFire1         = findViewById(R.id.btn_fire_1);
        btnFire2         = findViewById(R.id.btn_fire_2);
        btnFire3         = findViewById(R.id.btn_fire_3);
        btnFire4         = findViewById(R.id.btn_fire_4);
        btnFire5         = findViewById(R.id.btn_fire_5);
    }
}
