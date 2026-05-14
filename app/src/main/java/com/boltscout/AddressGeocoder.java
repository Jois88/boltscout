package com.boltscout;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Offline geocoder for Warsaw addresses.
 *
 * Strategy:
 *  1. Normalise the input (strip prefix, lower-case, remove Polish diacritics).
 *  2. Exact-match against ~280 Warsaw streets stored in STREET_DB.
 *  3. If matched, interpolate position by house number along the street's
 *     known bearing and approximate length.
 *  4. If not matched, try district/landmark name lookup.
 *  5. Fall back to Warsaw city centre with a flag.
 *
 * Coordinate columns in STREET_DB entry:
 *  [0] centre latitude
 *  [1] centre longitude
 *  [2] bearing in degrees (0 = north, 90 = east)
 *  [3] approximate street length in km
 */
public class AddressGeocoder {

    // Single source of truth for all Warsaw boundary constants
    static final String VIEWBOX        = "20.70,51.95,21.45,52.55";
    static final double BOUNDS_LAT_MIN = 51.95, BOUNDS_LAT_MAX = 52.55;
    static final double BOUNDS_LON_MIN = 20.70, BOUNDS_LON_MAX = 21.45;

    // Warsaw centre fallback
    private static final double[] CENTRE = {52.2297, 21.0122};

    // ─── LRU Geocoding Cache ───────────────────────────────────────────────────
    private static final int    CACHE_MAX  = 100;
    private static final String PREFS_NAME = "geocoder_cache";
    private static final String PREFS_KEY  = "lru_cache_v1";

    private static Context appContext = null;

    /** Access-order LRU: eldest entry is evicted once size exceeds CACHE_MAX. */
    private static final LinkedHashMap<String, double[]> geoCache =
        new LinkedHashMap<String, double[]>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, double[]> eldest) {
                return size() > CACHE_MAX;
            }
        };

    // House-number regex: captures optional prefix, street name, optional number
    private static final Pattern ADDR_RE = Pattern.compile(
        "^(?:ul\\.?|al\\.?|aleje?|pl\\.?|plac|os\\.?|osiedle|skw\\.?|rondo|"
      + "droga|szosa|trakt|bulwar|wybrzeże|wybrzeze)?\\s*"
      + "([A-ZĄĆĘŁŃÓŚŹŻ][\\wąćęłńóśźżĄĆĘŁŃÓŚŹŻ\\s\\-\\.]{1,50}?)\\s*"
      + "(\\d+)?[a-z]?(?:/\\d+)?$",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    // ─── Street database ───────────────────────────────────────────────────────
    // Key   : normalised name (lower-case, ASCII diacritics)
    // Value : {centreLat, centreLon, bearingDeg, lengthKm}
    private static final Map<String, double[]> STREET_DB = new HashMap<>();

    static {
        // ── Śródmieście ────────────────────────────────────────────────────────
        put("marszalkowska",           52.2278, 21.0022, 0,   2.2);
        put("nowy swiat",              52.2315, 21.0186, 0,   1.2);
        put("krakowskie przedmiescie", 52.2433, 21.0147, 0,   0.9);
        put("aleje jerozolimskie",     52.2283, 21.0203, 90,  7.0);
        put("jerozolimskie",           52.2283, 21.0203, 90,  7.0);
        put("swietokrzyska",           52.2344, 21.0097, 90,  1.5);
        put("zlota",                   52.2322, 20.9989, 90,  1.0);
        put("sienna",                  52.2294, 20.9967, 90,  0.8);
        put("wspolna",                 52.2233, 21.0033, 90,  1.1);
        put("emilii plater",           52.2328, 20.9978, 0,   1.0);
        put("hoza",                    52.2239, 21.0083, 90,  0.9);
        put("wilcza",                  52.2228, 21.0122, 90,  0.7);
        put("mokotowska",              52.2194, 21.0183, 0,   1.5);
        put("piekna",                  52.2208, 21.0167, 90,  0.7);
        put("nowogrodzka",             52.2261, 21.0033, 90,  1.3);
        put("sniadeckich",             52.2244, 21.0039, 90,  0.6);
        put("koszykowa",               52.2222, 21.0061, 90,  1.0);
        put("kredytowa",               52.2378, 21.0011, 90,  0.5);
        put("wierzbowa",               52.2406, 21.0028, 90,  0.5);
        put("mazowiecka",              52.2367, 21.0067, 0,   0.6);
        put("chmielna",                52.2344, 21.0050, 90,  0.8);
        put("bracka",                  52.2322, 21.0100, 0,   0.7);
        put("szpitalna",               52.2378, 21.0067, 0,   0.5);
        put("senatorska",              52.2406, 21.0072, 90,  0.9);
        put("bielanska",               52.2442, 21.0044, 0,   0.4);
        put("dluga",                   52.2467, 21.0028, 90,  1.0);
        put("freta",                   52.2519, 21.0039, 0,   0.5);
        put("miodowa",                 52.2456, 21.0078, 90,  0.6);
        put("podwale",                 52.2494, 21.0122, 90,  0.6);
        put("bonifraterska",           52.2472, 21.0172, 0,   0.6);
        put("andersa",                 52.2444, 21.0006, 0,   1.5);
        put("al jana pawla ii",        52.2372, 20.9939, 0,   3.5);
        put("jana pawla ii",           52.2372, 20.9939, 0,   3.5);
        put("al solidarnosci",         52.2500, 21.0039, 90,  4.0);
        put("solidarnosci",            52.2500, 21.0039, 90,  4.0);
        put("al niepodleglosci",       52.2094, 21.0147, 0,   5.0);
        put("niepodleglosci",          52.2094, 21.0147, 0,   5.0);
        put("al ujazdowskie",          52.2233, 21.0278, 0,   2.5);
        put("ujazdowskie",             52.2233, 21.0278, 0,   2.5);
        put("belwederska",             52.2139, 21.0289, 0,   1.5);
        put("czerniakowska",           52.2083, 21.0250, 0,   3.0);
        put("lazienkowska",            52.2194, 21.0333, 90,  2.0);
        put("trasa lazienkowska",      52.2194, 21.0333, 90,  2.0);
        put("rozbrat",                 52.2211, 21.0306, 90,  0.5);
        put("mysliwiecka",             52.2194, 21.0289, 0,   0.8);
        put("niecala",                 52.2361, 21.0011, 0,   0.4);
        put("mysia",                   52.2278, 20.9989, 90,  0.3);
        put("zgoda",                   52.2356, 21.0061, 0,   0.3);

        // ── Wola ──────────────────────────────────────────────────────────────
        put("wolska",                  52.2322, 20.9678, 90,  3.5);
        put("prosta",                  52.2344, 20.9867, 90,  2.5);
        put("kasprzaka",               52.2311, 20.9622, 0,   2.0);
        put("skierniewicka",           52.2289, 20.9489, 0,   1.2);
        put("plocka",                  52.2272, 20.9578, 0,   1.5);
        put("gorczewska",              52.2400, 20.9500, 90,  3.0);
        put("leszno",                  52.2478, 20.9844, 90,  2.5);
        put("mlynarska",               52.2417, 20.9711, 0,   1.5);
        put("ogrodowa",                52.2428, 20.9822, 90,  1.0);
        put("chłodna",                 52.2350, 20.9878, 90,  1.5);
        put("chlodna",                 52.2350, 20.9878, 90,  1.5);
        put("elektoralna",             52.2400, 20.9961, 90,  1.0);
        put("zelazna",                 52.2344, 20.9906, 0,   2.0);
        put("wronia",                  52.2333, 20.9844, 0,   2.5);
        put("towarowa",                52.2278, 20.9878, 0,   3.0);
        put("kolejowa",                52.2233, 20.9761, 90,  1.5);
        put("lucka",                   52.2250, 20.9944, 0,   0.7);
        put("grzybowska",              52.2339, 20.9978, 90,  1.5);
        put("przyokopowa",             52.2289, 20.9889, 90,  1.0);
        put("karolkowa",               52.2317, 20.9650, 0,   1.5);
        put("al prymasa tysiaclecia",  52.2233, 20.9706, 0,   3.0);
        put("prymasa tysiaclecia",     52.2233, 20.9706, 0,   3.0);

        // ── Żoliborz ──────────────────────────────────────────────────────────
        put("mickiewicza",             52.2783, 20.9822, 0,   2.0);
        put("slowackiego",             52.2811, 20.9756, 90,  2.5);
        put("krasinskeigo",            52.2739, 20.9878, 0,   1.5);
        put("krasinsiego",             52.2739, 20.9878, 0,   1.5);
        put("potocka",                 52.2856, 20.9811, 90,  2.0);
        put("czarnieckiego",           52.2794, 20.9844, 0,   1.0);
        put("zajaczka",                52.2878, 20.9922, 90,  1.5);
        put("pl wilsona",              52.2800, 20.9839, 0,   0.3);
        put("wilsona",                 52.2800, 20.9839, 0,   0.3);
        put("suzina",                  52.2728, 20.9944, 90,  0.8);
        put("marymont",                52.2900, 20.9733, 90,  1.5);
        put("powazkowska",             52.2689, 20.9717, 0,   1.5);
        put("gdanska",                 52.2644, 20.9933, 90,  1.5);
        put("kozmiana",                52.2700, 20.9800, 0,   0.7);
        put("inflancka",               52.2661, 20.9933, 0,   0.6);

        // ── Bielany ───────────────────────────────────────────────────────────
        put("zeromskiego",             52.3089, 20.9806, 0,   2.5);
        put("gwiazdzista",             52.3172, 20.9644, 90,  3.0);
        put("hery",                    52.3050, 20.9533, 90,  2.0);
        put("pulkowa",                 52.3128, 20.9556, 0,   2.0);
        put("wolczynska",              52.3194, 20.9233, 0,   3.5);
        put("marymoncka",              52.3167, 20.9617, 0,   3.0);
        put("dewajtis",                52.3239, 20.9411, 90,  1.5);
        put("przy agorze",             52.3122, 20.9489, 90,  0.8);
        put("lindego",                 52.2972, 20.9678, 0,   1.5);

        // ── Bemowo ────────────────────────────────────────────────────────────
        put("pelczynskiego",           52.2406, 20.9311, 0,   2.0);
        put("lazurowa",                52.2339, 20.9333, 90,  2.5);
        put("polczynska",              52.2372, 20.9400, 0,   2.5);
        put("radiowa",                 52.2394, 20.9456, 0,   1.5);
        put("szeligowska",             52.2456, 20.9289, 90,  2.0);
        put("powstancow slaskich",     52.2444, 20.9256, 0,   2.5);
        put("batalionow chlopskich",   52.2394, 20.9189, 0,   2.0);

        // ── Mokotów ───────────────────────────────────────────────────────────
        put("puławska",                52.1883, 21.0183, 0,   7.0);
        put("pulawska",                52.1883, 21.0183, 0,   7.0);
        put("rakowiecka",              52.1989, 21.0117, 90,  3.0);
        put("domaniewska",             52.1817, 21.0200, 90,  2.5);
        put("wisnniowa",               52.2039, 21.0139, 90,  1.5);
        put("wisnniowa",               52.2039, 21.0139, 90,  1.5);
        put("wiśniowa",                52.2039, 21.0139, 90,  1.5);
        put("wisniowa",                52.2039, 21.0139, 90,  1.5);
        put("woloska",                 52.1822, 20.9872, 90,  3.0);
        put("wołoska",                 52.1822, 20.9872, 90,  3.0);
        put("szturmowa",               52.1831, 21.0033, 0,   1.5);
        put("konstruktorska",          52.1844, 20.9989, 90,  2.0);
        put("bokserska",               52.1783, 21.0250, 0,   2.0);
        put("belgijska",               52.1906, 21.0306, 90,  1.5);
        put("podchorazych",            52.2033, 21.0117, 90,  1.5);
        put("odynica",                 52.1989, 21.0278, 0,   1.5);
        put("raclawicka",              52.2011, 21.0067, 90,  2.0);
        put("sobieski",                52.2006, 21.0233, 0,   2.5);
        put("sobieskiego",             52.2006, 21.0233, 0,   2.5);
        put("batuty",                  52.2000, 21.0383, 90,  1.0);
        put("dolna",                   52.2072, 21.0167, 0,   1.5);
        put("gagarina",                52.2056, 21.0311, 90,  2.0);
        put("kazimierzowska",          52.2028, 21.0144, 0,   1.5);
        put("malczewskiego",           52.1939, 21.0228, 90,  1.5);
        put("dworkowa",                52.2106, 21.0211, 90,  1.0);
        put("szarotki",                52.1917, 21.0239, 90,  0.8);
        put("spartanska",              52.1861, 21.0072, 0,   1.0);
        put("cybulskiego",             52.1961, 21.0139, 0,   1.0);
        put("woronicza",               52.2008, 21.0356, 90,  1.0);

        // ── Ursynów ───────────────────────────────────────────────────────────
        put("al kfn",                  52.1494, 21.0383, 90,  5.0);
        put("al komisji edukacji narodowej", 52.1494, 21.0383, 90, 5.0);
        put("komisji edukacji narodowej",    52.1494, 21.0383, 90, 5.0);
        put("kfn",                     52.1494, 21.0383, 90,  5.0);
        put("pileckiego",              52.1611, 21.0328, 90,  2.5);
        put("rosoła",                  52.1528, 21.0283, 0,   2.0);
        put("rosoła",                  52.1528, 21.0283, 0,   2.0);
        put("rosola",                  52.1528, 21.0283, 0,   2.0);
        put("wąwozowa",                52.1556, 21.0383, 90,  2.0);
        put("wawozowa",                52.1556, 21.0383, 90,  2.0);
        put("dereniowa",               52.1672, 21.0011, 90,  3.0);
        put("kazury",                  52.1433, 21.0267, 90,  1.5);
        put("kłobucka",                52.1756, 21.0008, 90,  2.0);
        put("klobucka",                52.1756, 21.0008, 90,  2.0);
        put("migdałowa",               52.1511, 21.0444, 90,  1.5);
        put("migdalowa",               52.1511, 21.0444, 90,  1.5);
        put("lasek brzozowy",          52.1522, 21.0383, 0,   1.0);
        put("imielin",                 52.1458, 21.0458, 0,   1.5);
        put("natolin",                 52.1519, 21.0567, 0,   2.0);
        put("nugat",                   52.1333, 21.0050, 90,  1.0);

        // ── Wilanów ───────────────────────────────────────────────────────────
        put("al wilanowska",           52.1733, 21.0456, 90,  4.0);
        put("wilanowska",              52.1733, 21.0456, 90,  4.0);
        put("al rzeczypospolitej",     52.1644, 21.0733, 90,  3.0);
        put("rzeczypospolitej",        52.1644, 21.0733, 90,  3.0);
        put("przyczolkowa",            52.1556, 21.0950, 90,  2.0);
        put("klimczaka",               52.1750, 21.0644, 90,  2.5);
        put("vogla",                   52.1622, 21.0728, 0,   1.5);
        put("gucin",                   52.1789, 21.0683, 90,  1.5);

        // ── Praga Północ ──────────────────────────────────────────────────────
        put("targowa",                 52.2533, 21.0583, 0,   2.5);
        put("zabkowska",               52.2556, 21.0522, 0,   1.5);
        put("ząbkowska",               52.2556, 21.0522, 0,   1.5);
        put("brzeska",                 52.2478, 21.0539, 90,  1.5);
        put("al rozyczka",             52.2617, 21.0556, 90,  0.8);
        put("stalowa",                 52.2572, 21.0622, 0,   1.0);
        put("konopacka",               52.2589, 21.0578, 90,  0.8);
        put("11 listopada",            52.2578, 21.0544, 90,  1.5);

        // ── Praga Południe ────────────────────────────────────────────────────
        put("grochowska",              52.2367, 21.0711, 90,  4.0);
        put("waszyngtona",             52.2333, 21.0556, 0,   2.5);
        put("ostrobramska",            52.2283, 21.0833, 90,  3.0);
        put("łukowska",                52.2283, 21.0750, 90,  2.0);
        put("lukowska",                52.2283, 21.0750, 90,  2.0);
        put("podskarbinska",           52.2378, 21.0667, 90,  2.5);
        put("podskarbińska",           52.2378, 21.0667, 90,  2.5);
        put("plowiecka",               52.2344, 21.1044, 90,  2.5);
        put("płowiecka",               52.2344, 21.1044, 90,  2.5);
        put("meissnera",               52.2406, 21.0733, 0,   1.5);
        put("wał miedzeszynski",       52.1994, 21.0889, 0,   6.0);
        put("wal miedzeszynski",       52.1994, 21.0889, 0,   6.0);
        put("zwyciezcow",              52.2272, 21.0639, 0,   2.5);
        put("zwycięzców",              52.2272, 21.0639, 0,   2.5);
        put("traktorowa",              52.2117, 21.0567, 90,  2.0);
        put("kamionek",                52.2489, 21.0583, 90,  1.5);
        put("przymierza",              52.2311, 21.0561, 0,   0.4);
        put("siennicka",               52.2450, 21.0700, 90,  3.0);
        put("wiatraczna",              52.2461, 21.0811, 0,   2.0);

        // ── Targówek ──────────────────────────────────────────────────────────
        put("radzyminska",             52.2922, 21.0767, 90,  3.5);
        put("radzymińska",             52.2922, 21.0767, 90,  3.5);
        put("kondratowicza",           52.2867, 21.0556, 90,  3.0);
        put("mieszka i",               52.2978, 21.0700, 90,  1.5);
        put("labiszynska",             52.2944, 21.0544, 0,   1.5);
        put("łabiszyńska",             52.2944, 21.0544, 0,   1.5);
        put("sw wincentego",           52.2833, 21.0533, 0,   2.5);
        put("swietego wincentego",     52.2833, 21.0533, 0,   2.5);

        // ── Białołęka ─────────────────────────────────────────────────────────
        put("modlinska",               52.3369, 21.0003, 0,   6.0);
        put("modlińska",               52.3369, 21.0003, 0,   6.0);
        put("berensona",               52.3222, 21.0167, 90,  2.5);
        put("glebocka",                52.3289, 21.0189, 0,   2.5);
        put("głębocka",                52.3289, 21.0189, 0,   2.5);
        put("swiatowida",              52.3208, 21.0117, 90,  2.0);
        put("światowida",              52.3208, 21.0117, 90,  2.0);
        put("podlaska",                52.3333, 21.0367, 90,  2.5);
        put("nowodworska",             52.3433, 20.9867, 90,  2.5);
        put("chlubna",                 52.3511, 21.0567, 90,  2.0);
        put("odkryta",                 52.3228, 21.0467, 90,  2.5);

        // ── Wawer ─────────────────────────────────────────────────────────────
        put("trakt lubelski",          52.1972, 21.1467, 0,   5.0);
        put("mrowcza",                 52.1833, 21.1333, 90,  2.5);
        put("mrówcza",                 52.1833, 21.1333, 90,  2.5);
        put("patriotow",               52.2100, 21.1100, 0,   3.0);
        put("patriotów",               52.2100, 21.1100, 0,   3.0);
        put("zerzeń",                  52.1778, 21.1256, 0,   2.0);
        put("zerzen",                  52.1778, 21.1256, 0,   2.0);
        put("pożaryskiego",            52.2200, 21.1083, 90,  2.5);
        put("pozaryskeigo",            52.2200, 21.1083, 90,  2.5);
        put("urocza",                  52.2011, 21.1400, 0,   2.0);

        // ── Włochy ────────────────────────────────────────────────────────────
        put("1 sierpnia",              52.2028, 20.9508, 0,   2.5);
        put("hynka",                   52.1906, 20.9489, 90,  2.0);
        put("lopuszanska",             52.1800, 20.9644, 0,   3.0);
        put("łopuszańska",             52.1800, 20.9644, 0,   3.0);
        put("instalatorow",            52.1867, 20.9489, 90,  1.5);
        put("instalatorów",            52.1867, 20.9489, 90,  1.5);
        put("szyszkowa",               52.2006, 20.9600, 0,   2.5);
        put("aleja krakowska",         52.1711, 20.9811, 0,   5.0);
        put("krakowska",               52.1711, 20.9811, 0,   5.0);

        // ── Ursus ─────────────────────────────────────────────────────────────
        put("regulska",                52.1950, 20.8978, 90,  2.0);
        put("posag 7 panien",          52.1983, 20.9211, 90,  1.5);
        put("herbu janina",            52.2050, 20.9067, 0,   2.0);
        put("astronautow",             52.1878, 20.9011, 90,  2.0);
        put("astronautów",             52.1878, 20.9011, 90,  2.0);

        // ── Special landmarks ─────────────────────────────────────────────────
        put("lotnisko chopina",        52.1672, 20.9678, 0,   1.5);
        put("port lotniczy",           52.1672, 20.9678, 0,   1.5);
        put("okecie",                  52.1672, 20.9678, 0,   1.5);
        put("okęcie",                  52.1672, 20.9678, 0,   1.5);
        put("dworzec centralny",       52.2286, 21.0033, 0,   0.3);
        put("warszawa centralna",      52.2286, 21.0033, 0,   0.3);
        put("stadion narodowy",        52.2395, 21.0447, 0,   0.5);
        put("stare miasto",            52.2499, 21.0122, 0,   0.8);
        put("old town",                52.2499, 21.0122, 0,   0.8);
        put("wilanow",                 52.1650, 21.0897, 0,   2.0);
        put("wilanów",                 52.1650, 21.0897, 0,   2.0);
        put("lazienki",                52.2150, 21.0350, 0,   1.5);
        put("łazienki",                52.2150, 21.0350, 0,   1.5);
        put("praga",                   52.2528, 21.0472, 0,   3.0);
        put("zoliborz",                52.2878, 20.9867, 0,   3.0);
        put("żoliborz",                52.2878, 20.9867, 0,   3.0);
        put("mokotow",                 52.1942, 21.0089, 0,   5.0);
        put("mokotów",                 52.1942, 21.0089, 0,   5.0);
        put("wola",                    52.2342, 20.9624, 0,   4.0);
        put("bielany",                 52.3203, 20.9478, 0,   5.0);
        put("bemowo",                  52.2511, 20.9116, 0,   4.0);
        put("ursus",                   52.1956, 20.8892, 0,   3.0);
        put("wlochy",                  52.1894, 20.9456, 0,   3.0);
        put("włochy",                  52.1894, 20.9456, 0,   3.0);
        put("ursynow",                 52.1456, 21.0278, 0,   5.0);
        put("ursynów",                 52.1456, 21.0278, 0,   5.0);
        put("targowek",                52.2978, 21.0678, 0,   4.0);
        put("targówek",                52.2978, 21.0678, 0,   4.0);
        put("bialoleka",               52.3378, 21.0256, 0,   6.0);
        put("białołęka",               52.3378, 21.0256, 0,   6.0);
        put("wawer",                   52.1956, 21.1556, 0,   6.0);
        put("wesola",                  52.2456, 21.2056, 0,   5.0);
        put("wesoła",                  52.2456, 21.2056, 0,   5.0);
        put("rembertow",               52.2456, 21.1556, 0,   5.0);
        put("rembertów",               52.2456, 21.1556, 0,   5.0);
        put("srodmiescie",             52.2297, 21.0122, 0,   3.0);
        put("śródmieście",             52.2297, 21.0122, 0,   3.0);
        put("centrum",                 52.2297, 21.0122, 0,   2.0);

        // Pre-seed the Nominatim LRU cache with key Warsaw landmarks
        preSeed();
    }

    private static void put(String name, double lat, double lon, double bearing, double lenKm) {
        STREET_DB.put(norm(name), new double[]{lat, lon, bearing, lenKm});
    }

    // ─── Cache helpers ─────────────────────────────────────────────────────────

    /**
     * Must be called once from Service.onServiceConnected() to wire up
     * SharedPreferences so the cache persists between shifts.
     */
    public static void init(Context ctx) {
        appContext = ctx.getApplicationContext();
        loadCacheFromPrefs();
    }

    /** Hard-coded Warsaw landmarks seeded into the Nominatim cache at class load. */
    private static void preSeed() {
        geoCache.put("lotnisko chopina",   new double[]{52.1672, 20.9679});
        geoCache.put("warszawa centralna", new double[]{52.2297, 20.9836});
        geoCache.put("warszawa zachodnia", new double[]{52.2200, 20.9624});
        geoCache.put("zlote tarasy",       new double[]{52.2285, 20.9842});
        geoCache.put("stadion narodowy",   new double[]{52.2395, 21.0440});
        geoCache.put("warszawa wschodnia",          new double[]{52.2519, 21.0450});
        geoCache.put("centrum",                     new double[]{52.2297, 21.0122});
        geoCache.put("warszawa zachodnia bus station", new double[]{52.2200, 20.9624});
        geoCache.put("warsaw west railway station", new double[]{52.2200, 20.9624});
    }

    /** Merges previously persisted entries into the live cache on app start. */
    private static void loadCacheFromPrefs() {
        if (appContext == null) return;
        try {
            SharedPreferences prefs =
                appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String json = prefs.getString(PREFS_KEY, null);
            if (json == null) return;
            JSONObject obj = new JSONObject(json);
            java.util.Iterator<String> it = obj.keys();
            synchronized (geoCache) {
                while (it.hasNext()) {
                    String k = it.next();
                    JSONArray arr = obj.getJSONArray(k);
                    geoCache.put(k, new double[]{arr.getDouble(0), arr.getDouble(1)});
                }
            }
        } catch (Exception ignored) {}
    }

    /** Serialises the full cache to SharedPreferences (called after each new entry). */
    private static void persistCache() {
        if (appContext == null) return;
        try {
            JSONObject obj = new JSONObject();
            synchronized (geoCache) {
                for (Map.Entry<String, double[]> e : geoCache.entrySet()) {
                    JSONArray arr = new JSONArray();
                    arr.put(e.getValue()[0]);
                    arr.put(e.getValue()[1]);
                    obj.put(e.getKey(), arr);
                }
            }
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREFS_KEY, obj.toString())
                .apply();
        } catch (Exception ignored) {}
    }

    /**
     * Cache key: lowercase, diacritics stripped, punctuation removed,
     * city-name suffix ("warszawa") stripped.
     */
    private static String cacheKey(String address) {
        if (address == null) return "";
        // Strip trailing city suffix: ", Warszawa" or "Warszawa" alone
        String k = address.replaceAll("(?i),?\\s*warszawa\\s*$", "").trim();
        // Remove punctuation (keep letters, digits, whitespace)
        k = k.replaceAll("[^\\p{L}\\d\\s]", " ");
        // Normalise diacritics and whitespace via existing norm()
        return norm(k);
    }

    // ─── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns {lat, lon} for the given Warsaw address string.
     * Never returns null; falls back to city centre with a note in the flag.
     */
    public static double[] geocode(String address) {
        if (address == null || address.trim().isEmpty()) return CENTRE.clone();

        // Strip business-name prefix by scanning ALL comma segments.
        // Pass 1: find the last segment that contains a digit → street + house number.
        // Pass 2 (fallback): no digit in any segment → take the FIRST segment, which
        //         is the primary landmark name (e.g. station, venue).
        // After either pass: strip trailing "platform N" venue suffixes.
        // Examples:
        //   "Gojo, Aleja Jana Pawła II 45A, Warszawa"
        //       → "Aleja Jana Pawła II 45A" has digit → "Aleja Jana Pawła II 45A, Warszawa"
        //   "Warsaw West Railway Station platform 9, Warsaw West Railway Station platform 9"
        //       → digit found → strip "platform 9" → "Warsaw West Railway Station, Warszawa"
        //   "Warszawa Zachodnia Bus Station, Warsaw West Railway Station"
        //       → no digit → first segment → "Warszawa Zachodnia Bus Station, Warszawa"
        String input = address.trim();

        // Extract postal code FIRST so "Warszawa 04-384" loses its digit before
        // segment selection — otherwise the city+postal segment is mistakenly
        // chosen over the actual street segment (e.g. "Ulica Wiatraczna 21").
        Matcher pcMatcher = Pattern.compile("\\d{2}-\\d{3}").matcher(input);
        String postalCode = pcMatcher.find() ? pcMatcher.group() : null;
        input = input.replaceAll(",?\\s*\\d{2}-\\d{3}", "").trim();

        if (input.contains(",")) {
            String[] parts = input.split(",");
            String streetSegment = null;

            // Pass 1: last segment with a digit (street + house number)
            for (int i = parts.length - 1; i >= 0; i--) {
                if (parts[i].trim().matches(".*\\d.*")) {
                    streetSegment = parts[i].trim();
                    break;
                }
            }

            // Pass 2: no digit found → take the LAST segment that isn't a bare
            // city/country name.  This correctly extracts the street from addresses like
            // "Bollywood Lounge Restaurant, Bar & Sheesha Bar, Nowogrodzka, Warsaw, Poland"
            // → "Nowogrodzka", while still handling landmark-only formats correctly.
            if (streetSegment == null) {
                final String[] CITY_COUNTRY = {
                    "warsaw", "poland", "warszawa", "polska", "mazowieckie",
                    "masovian voivodeship", "województwo mazowieckie"
                };
                for (int i = parts.length - 1; i >= 0; i--) {
                    String seg = parts[i].trim();
                    String segLower = seg.toLowerCase();
                    boolean isCityOrCountry = false;
                    for (String skip : CITY_COUNTRY) {
                        if (segLower.equals(skip)) { isCityOrCountry = true; break; }
                    }
                    if (!isCityOrCountry && !seg.isEmpty()) {
                        streetSegment = seg;
                        break;
                    }
                }
                if (streetSegment == null) streetSegment = parts[0].trim();
            }

            // Strip trailing "platform N" (and similar numeric venue suffixes)
            streetSegment = streetSegment
                .replaceAll("(?i)\\s+platform\\s+\\d+\\s*$", "").trim();

            if (!streetSegment.isEmpty()) {
                input = streetSegment + ", Warszawa";
            }
        }
        // Ensure city context is present for Nominatim fallback
        if (!input.toLowerCase().contains("warszawa")) {
            input = input + ", Warszawa";
        }

        // 1. Parse street name and house number
        Matcher m = ADDR_RE.matcher(input);
        String streetName = null;
        int houseNum = 0;

        if (m.find()) {
            streetName = m.group(1);
            String numStr = m.group(2);
            if (numStr != null) {
                try { houseNum = Integer.parseInt(numStr); } catch (NumberFormatException ignored) {}
            }
        } else {
            streetName = input;
        }

        // 2. Exact match on normalised name
        String key = norm(streetName);
        double[] entry = STREET_DB.get(key);

        // 3. Prefix / suffix fuzzy match if exact failed
        if (entry == null) {
            entry = fuzzyFind(key);
        }

        if (entry != null) return interpolate(entry, houseNum);

        // 4. Offline lookup failed — fall back to Geoapify API
        double[] coord = geocodeGeoapify(input, postalCode);
        return coord != null ? coord : CENTRE.clone();
    }

    /**
     * Geoapify Geocoding API lookup with LRU cache.
     *
     * Cache behaviour: cache hit returns instantly; every valid result is stored
     * in the LRU cache and persisted to SharedPreferences. No TTL.
     *
     * @param query      cleaned address ("Ulica Siennicka 50, Warszawa")
     * @param postalCode unused — Geoapify filtering is handled via countrycode:pl
     * @return {lat, lon} on success, null on failure or discarded fallback
     */
    private static double[] geocodeGeoapify(String query, String postalCode) {
        String key = cacheKey(query);

        synchronized (geoCache) {
            double[] cached = geoCache.get(key);
            if (cached != null) return cached.clone();
        }

        ExecutorService exec = Executors.newSingleThreadExecutor();
        Future<double[]> future = exec.submit(() -> {
            try {
                String url = "https://api.geoapify.com/v1/geocode/search"
                    + "?text=" + URLEncoder.encode(query, "UTF-8")
                    + "&filter=countrycode:pl"
                    + "&bias=proximity:21.0122,52.2297"
                    + "&limit=1"
                    + "&apiKey=" + BuildConfig.GEOAPIFY_KEY;

                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);

                if (conn.getResponseCode() != 200) {
                    conn.disconnect();
                    return null;
                }

                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                conn.disconnect();

                JSONObject response = new JSONObject(sb.toString());
                JSONArray features = response.optJSONArray("features");
                if (features == null || features.length() == 0) return null;

                JSONArray coords = features.getJSONObject(0)
                    .getJSONObject("geometry")
                    .getJSONArray("coordinates");
                double lon = coords.getDouble(0);
                double lat = coords.getDouble(1);

                // Discard exact Warsaw-centre coordinate — Geoapify returns this
                // when the address cannot be resolved within Poland.
                if (lat == 52.2297 && lon == 21.0122) return null;

                double[] result = new double[]{lat, lon};
                synchronized (geoCache) { geoCache.put(key, result); }
                persistCache();
                return result.clone();
            } catch (Exception ignored) {}
            return null;
        });
        exec.shutdown();
        try {
            return future.get(35, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * If pickup and dropoff pins would overlap (within 0.002° of each other),
     * offsets the pickup pin by +0.003 lat so both markers remain visible.
     * Modifies the pickup array in-place; safe to call with null arguments.
     */
    public static void offsetIfOverlapping(double[] pickup, double[] dropoff) {
        if (pickup == null || dropoff == null) return;
        if (Math.abs(pickup[0] - dropoff[0]) < 0.005
                && Math.abs(pickup[1] - dropoff[1]) < 0.005) {
            pickup[0] += 0.008;
        }
    }

    private static double[] interpolate(double[] entry, int houseNum) {
        if (houseNum <= 0) return new double[]{entry[0], entry[1]};

        double lat      = entry[0];
        double lon      = entry[1];
        double bearing  = entry[2];
        double lenKm    = entry[3];

        // Assume house numbers run 1..200 over the street length
        // progress ∈ [-0.5, +0.5] (centred on street midpoint)
        double progress = Math.min(houseNum / 200.0, 1.0) - 0.5;
        double offsetKm = progress * lenKm;

        double bearingRad = Math.toRadians(bearing);
        double dLat = (offsetKm / 111.0) * Math.cos(bearingRad);
        double dLon = (offsetKm / (111.0 * Math.cos(Math.toRadians(lat)))) * Math.sin(bearingRad);

        // Clamp to Warsaw bounds
        double newLat = Math.max(BOUNDS_LAT_MIN, Math.min(BOUNDS_LAT_MAX, lat + dLat));
        double newLon = Math.max(BOUNDS_LON_MIN, Math.min(BOUNDS_LON_MAX, lon + dLon));
        return new double[]{newLat, newLon};
    }

    /** Case-insensitive partial key lookup. */
    private static double[] fuzzyFind(String key) {
        // Try progressively shorter prefixes (at least 5 chars)
        for (int len = key.length(); len >= 5; len--) {
            String prefix = key.substring(0, len);
            for (Map.Entry<String, double[]> e : STREET_DB.entrySet()) {
                if (e.getKey().startsWith(prefix)) return e.getValue();
            }
        }
        // Substring anywhere
        for (Map.Entry<String, double[]> e : STREET_DB.entrySet()) {
            if (e.getKey().contains(key) || key.contains(e.getKey())) return e.getValue();
        }
        return null;
    }

    /** Normalise to lower-case ASCII (strip diacritics). */
    static String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase()
                .replace("ą","a").replace("ć","c").replace("ę","e")
                .replace("ł","l").replace("ń","n").replace("ó","o")
                .replace("ś","s").replace("ź","z").replace("ż","z")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
