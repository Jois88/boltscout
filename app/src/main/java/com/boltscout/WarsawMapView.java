package com.boltscout;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/**
 * Custom View that draws a fully code-generated SVG-style map of Warsaw.
 *
 * Layers (bottom → top):
 *  1. Dark background
 *  2. Warsaw municipality outline (filled polygon)
 *  3. District sub-zones (subtle alternating fills)
 *  4. Wisła river (wide blue path)
 *  5. Expressways / ring roads (gold)
 *  6. Major arterial roads (light grey)
 *  7. Secondary roads (dim grey)
 *  8. District name labels
 *  9. Route line between pins (dashed)
 * 10. Pickup pin (green teardrop) + Dropoff pin (red teardrop)
 * 11. Compass rose (top-right corner)
 *
 * Coordinate system:
 *   latMin=52.09  latMax=52.37  lonMin=20.85  lonMax=21.27
 *   x = (lon − lonMin) / (lonMax − lonMin) × width
 *   y = (latMax − lat) / (latMax − latMin) × height
 */
public class WarsawMapView extends View {

    // ── Map bounds ────────────────────────────────────────────────────────────
    private static final double LAT_MIN = 52.09;
    private static final double LAT_MAX = 52.37;
    private static final double LON_MIN = 20.85;
    private static final double LON_MAX = 21.27;

    // ── Warsaw municipality boundary (lat,lon pairs, clockwise) ───────────────
    private static final double[][] CITY_BOUNDARY = {
        {52.368, 20.960},{52.370, 21.000},{52.367, 21.040},{52.360, 21.082},
        {52.351, 21.118},{52.340, 21.153},{52.328, 21.188},{52.312, 21.222},
        {52.295, 21.252},{52.278, 21.268},{52.258, 21.260},{52.240, 21.244},
        {52.222, 21.225},{52.202, 21.205},{52.182, 21.183},{52.162, 21.163},
        {52.143, 21.144},{52.125, 21.128},{52.108, 21.112},{52.095, 21.088},
        {52.093, 21.052},{52.096, 21.012},{52.100, 20.972},{52.108, 20.932},
        {52.118, 20.896},{52.132, 20.864},{52.154, 20.856},{52.182, 20.853},
        {52.212, 20.854},{52.242, 20.856},{52.272, 20.860},{52.302, 20.870},
        {52.328, 20.890},{52.350, 20.915},{52.364, 20.940},{52.368, 20.960}
    };

    // ── Wisła river centre-line (S → N) ───────────────────────────────────────
    private static final double[][] WISLA = {
        {52.094, 21.110},{52.112, 21.096},{52.132, 21.082},{52.152, 21.067},
        {52.172, 21.054},{52.192, 21.044},{52.208, 21.037},{52.220, 21.030},
        {52.230, 21.026},{52.240, 21.022},{52.252, 21.018},{52.266, 21.013},
        {52.282, 21.007},{52.298, 21.001},{52.314, 20.996},{52.330, 20.990},
        {52.346, 20.984},{52.362, 20.978},{52.370, 20.975}
    };

    // ── Roads: each inner array is one polyline of {lat,lon} pairs ────────────
    // Al. Jerozolimskie (E–W main artery)
    private static final double[][] R_JEROZOLIMSKIE = {
        {52.228,20.878},{52.228,20.920},{52.228,20.958},{52.228,20.990},
        {52.228,21.020},{52.228,21.055},{52.228,21.092},{52.228,21.135},
        {52.227,21.165}
    };
    // Marszałkowska – Jana Pawła II (N–S through centre)
    private static final double[][] R_MARSZALKOWSKA = {
        {52.190,21.002},{52.205,21.002},{52.218,21.002},{52.228,21.001},
        {52.238,20.999},{52.248,20.997},{52.260,20.995},{52.272,20.993}
    };
    // Al. Solidarności (E–W, north of centre)
    private static final double[][] R_SOLIDARNOSCI = {
        {52.248,20.940},{52.249,20.968},{52.250,20.998},{52.251,21.028},
        {52.253,21.058},{52.255,21.082}
    };
    // Al. Niepodległości (N–S, south)
    private static final double[][] R_NIEPODLEGLOSCI = {
        {52.228,21.014},{52.215,21.013},{52.202,21.013},{52.188,21.013},
        {52.174,21.013},{52.160,21.013}
    };
    // Puławska (S from centre)
    private static final double[][] R_PULAWSKA = {
        {52.226,21.008},{52.212,21.010},{52.198,21.013},{52.184,21.016},
        {52.168,21.020},{52.152,21.024}
    };
    // Trasa Łazienkowska (E–W tunnel/bridge)
    private static final double[][] R_LAZIENKOWSKA = {
        {52.220,20.962},{52.220,20.990},{52.220,21.020},{52.220,21.050},
        {52.220,21.080},{52.220,21.105}
    };
    // Wolska (W from centre)
    private static final double[][] R_WOLSKA = {
        {52.233,20.998},{52.233,20.970},{52.232,20.940},{52.230,20.912}
    };
    // Al. Jana Pawła II (lower N–S, west side of centre)
    private static final double[][] R_JANA_PAWLA = {
        {52.190,20.994},{52.205,20.994},{52.218,20.994},{52.230,20.993},
        {52.242,20.993},{52.256,20.993}
    };
    // Modlińska (N towards Białołęka)
    private static final double[][] R_MODLINSKA = {
        {52.282,21.003},{52.298,21.004},{52.314,21.004},{52.332,21.003},
        {52.350,21.002}
    };
    // Grochowska (E in Praga Południe)
    private static final double[][] R_GROCHOWSKA = {
        {52.237,21.048},{52.237,21.070},{52.236,21.098},{52.235,21.125}
    };
    // Al. Wilanowska (S–SE to Wilanów)
    private static final double[][] R_WILANOWSKA = {
        {52.216,21.030},{52.202,21.040},{52.190,21.052},{52.178,21.062},
        {52.166,21.074},{52.155,21.082}
    };
    // Górczewska (W in Wola/Bemowo)
    private static final double[][] R_GORCZEWSKA = {
        {52.240,20.998},{52.240,20.970},{52.240,20.942},{52.240,20.912},
        {52.240,20.882}
    };
    // S2 expressway (south ring road)
    private static final double[][] R_S2 = {
        {52.214,20.862},{52.200,20.892},{52.190,20.932},{52.183,20.978},
        {52.180,21.028},{52.182,21.080},{52.186,21.122},{52.192,21.162}
    };
    // S8 expressway (NW)
    private static final double[][] R_S8 = {
        {52.230,20.860},{52.235,20.898},{52.241,20.930},{52.248,20.958},
        {52.258,20.990},{52.266,21.018}
    };
    // Radzymińska (NE, Targówek)
    private static final double[][] R_RADZYMINSKA = {
        {52.262,21.052},{52.272,21.062},{52.284,21.072},{52.296,21.082}
    };
    // Wał Miedzeszyński (along east Wisła bank)
    private static final double[][] R_WAL = {
        {52.134,21.080},{52.155,21.076},{52.175,21.070},{52.195,21.060},
        {52.212,21.048},{52.225,21.038}
    };
    // Krakowskie Przedmieście / Nowy Świat (N–S east spine)
    private static final double[][] R_NOWY_SWIAT = {
        {52.250,21.014},{52.244,21.016},{52.238,21.018},{52.232,21.020},
        {52.224,21.020}
    };
    // Al. Ujazdowskie
    private static final double[][] R_UJAZDOWSKIE = {
        {52.226,21.028},{52.218,21.029},{52.210,21.030},{52.202,21.030}
    };

    // ── District label positions {lat, lon, name} ─────────────────────────────
    private static final Object[][] DISTRICT_LABELS = {
        {52.231, 21.000, "Śródmieście"},
        {52.234, 20.958, "Wola"},
        {52.278, 20.986, "Żoliborz"},
        {52.318, 20.952, "Bielany"},
        {52.252, 20.918, "Bemowo"},
        {52.196, 20.900, "Ursus/Włochy"},
        {52.192, 21.010, "Mokotów"},
        {52.150, 21.030, "Ursynów"},
        {52.164, 21.082, "Wilanów"},
        {52.258, 21.058, "Praga Pn."},
        {52.228, 21.072, "Praga Pd."},
        {52.290, 21.070, "Targówek"},
        {52.196, 21.118, "Wawer"},
        {52.340, 21.028, "Białołęka"},
    };

    // ── State ─────────────────────────────────────────────────────────────────
    private double pickupLat, pickupLon;
    private double dropoffLat, dropoffLon;
    private boolean hasPins = false;

    // ── Paints ────────────────────────────────────────────────────────────────
    private final Paint paintCityFill   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintCityStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintWater      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintExpressway = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintRoadMajor  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintRoadMinor  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintLabel      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintPickup     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintDropoff    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintPinText    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintRoute      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintCompass    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintPinShadow  = new Paint(Paint.ANTI_ALIAS_FLAG);

    public WarsawMapView(Context context) {
        super(context);
        init();
    }

    public WarsawMapView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public WarsawMapView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // City fill
        paintCityFill.setStyle(Paint.Style.FILL);
        paintCityFill.setColor(0xFF1C2640);

        // City stroke
        paintCityStroke.setStyle(Paint.Style.STROKE);
        paintCityStroke.setColor(0xFF2A3860);
        paintCityStroke.setStrokeWidth(2f);

        // Wisła
        paintWater.setStyle(Paint.Style.STROKE);
        paintWater.setColor(0xFF1550A0);
        paintWater.setStrokeCap(Paint.Cap.ROUND);
        paintWater.setStrokeJoin(Paint.Join.ROUND);

        // Expressways
        paintExpressway.setStyle(Paint.Style.STROKE);
        paintExpressway.setColor(0xFFFFD700);
        paintExpressway.setStrokeCap(Paint.Cap.ROUND);
        paintExpressway.setStrokeJoin(Paint.Join.ROUND);

        // Major roads
        paintRoadMajor.setStyle(Paint.Style.STROKE);
        paintRoadMajor.setColor(0xFFB8C0D0);
        paintRoadMajor.setStrokeCap(Paint.Cap.ROUND);
        paintRoadMajor.setStrokeJoin(Paint.Join.ROUND);

        // Minor roads
        paintRoadMinor.setStyle(Paint.Style.STROKE);
        paintRoadMinor.setColor(0xFF404860);
        paintRoadMinor.setStrokeCap(Paint.Cap.ROUND);
        paintRoadMinor.setStrokeJoin(Paint.Join.ROUND);

        // District labels
        paintLabel.setTextSize(22f);
        paintLabel.setColor(0xFF606888);
        paintLabel.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
        paintLabel.setTextAlign(Paint.Align.CENTER);

        // Pickup pin (green)
        paintPickup.setStyle(Paint.Style.FILL);
        paintPickup.setColor(0xFF00E676);

        // Dropoff pin (red)
        paintDropoff.setStyle(Paint.Style.FILL);
        paintDropoff.setColor(0xFFFF1744);

        // Pin text
        paintPinText.setColor(Color.WHITE);
        paintPinText.setTextAlign(Paint.Align.CENTER);
        paintPinText.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        // Shadow for pins
        paintPinShadow.setStyle(Paint.Style.FILL);
        paintPinShadow.setColor(0x44000000);

        // Route dashed line
        paintRoute.setStyle(Paint.Style.STROKE);
        paintRoute.setColor(0xAAFFFFFF);
        paintRoute.setStrokeCap(Paint.Cap.ROUND);
        paintRoute.setPathEffect(new DashPathEffect(new float[]{14f, 10f}, 0f));

        // Compass
        paintCompass.setColor(0xFF8090B0);
        paintCompass.setTextAlign(Paint.Align.CENTER);
        paintCompass.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    public void setPins(double pickupLat, double pickupLon,
                        double dropoffLat, double dropoffLon) {
        this.pickupLat  = pickupLat;
        this.pickupLon  = pickupLon;
        this.dropoffLat = dropoffLat;
        this.dropoffLon = dropoffLon;
        this.hasPins    = true;
        invalidate();
    }

    public void clearPins() {
        hasPins = false;
        invalidate();
    }

    // ── Drawing ────────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        // Scale-dependent sizes
        float density  = getResources().getDisplayMetrics().density;
        float wislaW   = dp(10, density);
        float majorW   = dp(2.5f, density);
        float minorW   = dp(1.2f, density);
        float expressW = dp(3, density);
        float routeW   = dp(2, density);
        float pinR     = dp(13, density);
        float labelSz  = dp(8, density);
        float pinTxtSz = dp(10, density);

        paintWater.setStrokeWidth(wislaW);
        paintExpressway.setStrokeWidth(expressW);
        paintRoadMajor.setStrokeWidth(majorW);
        paintRoadMinor.setStrokeWidth(minorW);
        paintRoute.setStrokeWidth(routeW);
        paintLabel.setTextSize(labelSz);
        paintPinText.setTextSize(pinTxtSz);
        paintCompass.setTextSize(dp(9, density));

        // 1. Background
        canvas.drawColor(0xFF0F1626);

        // 2. City fill + stroke
        Path cityPath = buildPath(CITY_BOUNDARY, w, h, true);
        canvas.drawPath(cityPath, paintCityFill);
        canvas.drawPath(cityPath, paintCityStroke);

        // 3. Wisła river (draw twice for a glow effect)
        Paint wislaGlow = new Paint(paintWater);
        wislaGlow.setStrokeWidth(wislaW * 2.2f);
        wislaGlow.setColor(0x220A2A5E);
        canvas.drawPath(buildPath(WISLA, w, h, false), wislaGlow);
        canvas.drawPath(buildPath(WISLA, w, h, false), paintWater);

        // 4. Expressways
        canvas.drawPath(buildPath(R_S2, w, h, false), paintExpressway);
        canvas.drawPath(buildPath(R_S8, w, h, false), paintExpressway);

        // 5. Major arterial roads
        double[][][] majorRoads = {
            R_JEROZOLIMSKIE, R_MARSZALKOWSKA, R_SOLIDARNOSCI, R_JANA_PAWLA,
            R_NIEPODLEGLOSCI, R_PULAWSKA, R_LAZIENKOWSKA, R_WOLSKA,
            R_NOWY_SWIAT, R_UJAZDOWSKIE, R_MODLINSKA, R_GROCHOWSKA,
            R_WILANOWSKA, R_GORCZEWSKA
        };
        for (double[][] road : majorRoads) {
            canvas.drawPath(buildPath(road, w, h, false), paintRoadMajor);
        }

        // 6. Secondary roads
        canvas.drawPath(buildPath(R_RADZYMINSKA, w, h, false), paintRoadMinor);
        canvas.drawPath(buildPath(R_WAL, w, h, false), paintRoadMinor);

        // 7. District labels
        for (Object[] lbl : DISTRICT_LABELS) {
            float lx = toX((double) lbl[1], w);
            float ly = toY((double) lbl[0], h);
            canvas.drawText((String) lbl[2], lx, ly, paintLabel);
        }

        // 8. Route line between pins
        if (hasPins) {
            float px = toX(pickupLon, w);
            float py = toY(pickupLat, h);
            float dx = toX(dropoffLon, w);
            float dy = toY(dropoffLat, h);
            canvas.drawLine(px, py, dx, dy, paintRoute);
        }

        // 9. Pins
        if (hasPins) {
            drawPin(canvas, pickupLon, pickupLat, w, h, paintPickup, "P", pinR, density);
            drawPin(canvas, dropoffLon, dropoffLat, w, h, paintDropoff, "D", pinR, density);
        }

        // 10. Compass rose (top-right corner)
        drawCompass(canvas, w - dp(24, density), dp(24, density), dp(14, density), density);

        // 11. Scale bar (bottom-left)
        drawScaleBar(canvas, dp(12, density), h - dp(14, density), w, h, density);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Path buildPath(double[][] pts, int w, int h, boolean close) {
        Path p = new Path();
        if (pts.length == 0) return p;
        p.moveTo(toX(pts[0][1], w), toY(pts[0][0], h));
        for (int i = 1; i < pts.length; i++) {
            p.lineTo(toX(pts[i][1], w), toY(pts[i][0], h));
        }
        if (close) p.close();
        return p;
    }

    /** Draw a teardrop map pin centred at the given lat/lon. */
    private void drawPin(Canvas canvas, double lon, double lat,
                         int w, int h, Paint fillPaint, String letter,
                         float r, float density) {
        float cx = toX(lon, w);
        float cy = toY(lat, h);

        // Shadow
        paintPinShadow.setMaskFilter(null);
        canvas.drawCircle(cx + dp(2, density), cy + r * 1.6f + dp(2, density),
                          r * 0.35f, paintPinShadow);

        // Teardrop tail
        Path tail = new Path();
        float tailTop = cy + r * 0.6f;
        float tailBot = cy + r * 1.9f;
        tail.moveTo(cx - r * 0.35f, tailTop);
        tail.lineTo(cx, tailBot);
        tail.lineTo(cx + r * 0.35f, tailTop);
        tail.close();
        canvas.drawPath(tail, fillPaint);

        // Circle head
        canvas.drawCircle(cx, cy, r, fillPaint);

        // White inner circle
        Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
        inner.setStyle(Paint.Style.FILL);
        inner.setColor(0x33FFFFFF);
        canvas.drawCircle(cx, cy, r * 0.55f, inner);

        // Letter
        paintPinText.setTextSize(r * 1.1f);
        float textY = cy + (paintPinText.descent() - paintPinText.ascent()) / 2f
                      - paintPinText.descent();
        canvas.drawText(letter, cx, textY, paintPinText);
    }

    /** Simple N-arrow compass rose. */
    private void drawCompass(Canvas canvas, float cx, float cy, float r, float density) {
        paintCompass.setStyle(Paint.Style.FILL);

        // North arrow (white)
        paintCompass.setColor(0xFFFFFFFF);
        Path north = new Path();
        north.moveTo(cx, cy - r);
        north.lineTo(cx - r * 0.35f, cy);
        north.lineTo(cx + r * 0.35f, cy);
        north.close();
        canvas.drawPath(north, paintCompass);

        // South arrow (dim)
        paintCompass.setColor(0xFF404860);
        Path south = new Path();
        south.moveTo(cx, cy + r);
        south.lineTo(cx - r * 0.35f, cy);
        south.lineTo(cx + r * 0.35f, cy);
        south.close();
        canvas.drawPath(south, paintCompass);

        // N label
        paintCompass.setColor(0xFFFFFFFF);
        paintCompass.setTextSize(r * 0.75f);
        paintCompass.setStyle(Paint.Style.FILL);
        canvas.drawText("N", cx, cy - r - dp(3, density), paintCompass);
    }

    /** Draws an approximate 5 km scale bar. */
    private void drawScaleBar(Canvas canvas, float x, float y, int w, int h, float density) {
        // 5 km in longitude degrees at Warsaw latitude ≈ 0.075 degrees
        float barW = (float)(0.075 / (LON_MAX - LON_MIN) * w);

        Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        bar.setColor(0xFFB0B8D0);
        bar.setStrokeWidth(dp(1.5f, density));
        bar.setStyle(Paint.Style.STROKE);
        canvas.drawLine(x, y, x + barW, y, bar);
        canvas.drawLine(x, y - dp(3, density), x, y + dp(3, density), bar);
        canvas.drawLine(x + barW, y - dp(3, density), x + barW, y + dp(3, density), bar);

        bar.setStyle(Paint.Style.FILL);
        bar.setTextSize(dp(8, density));
        bar.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("5 km", x + barW / 2f, y - dp(5, density), bar);
    }

    // ── Coordinate helpers ────────────────────────────────────────────────────

    float toX(double lon, int w) {
        return (float)((lon - LON_MIN) / (LON_MAX - LON_MIN) * w);
    }

    float toY(double lat, int h) {
        return (float)((LAT_MAX - lat) / (LAT_MAX - LAT_MIN) * h);
    }

    private float dp(float dp, float density) {
        return dp * density;
    }
}
