package com.boltscout;

import org.junit.Test;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class GeocoderTest {

    private static final double LAT_MIN   = 51.95, LAT_MAX = 52.55;
    private static final double LON_MIN   = 20.70, LON_MAX = 21.45;
    private static final double CENTRE_LAT = 52.2297, CENTRE_LON = 21.0122;

    // [pickup, dropoff]
    private static final String[][] PAIRS = {
        // SHORT SAME-DISTRICT RIDES (1-10)
        {"Złota 44, Warszawa 00-120",                          "Marszałkowska 140, Warszawa 00-061"},
        {"Puławska 145, Warszawa 02-715",                      "Woronicza 17, Warszawa 02-640"},
        {"Wolska 80, Warszawa 01-140",                         "Młynarska 8, Warszawa 01-205"},
        {"Grochowska 207, Warszawa 04-067",                    "Kamionek 11, Warszawa 03-812"},
        {"Targowa 74, Warszawa 03-734",                        "Ząbkowska 23, Warszawa 03-736"},
        {"Mickiewicza 27, Warszawa 01-517",                    "Słowackiego 19, Warszawa 01-592"},
        {"Komisji Edukacji Narodowej 36, Warszawa 02-768",     "Rosoła 18, Warszawa 02-786"},
        {"Lazurowa 14, Warszawa 01-315",                       "Powstańców Śląskich 106, Warszawa 01-466"},
        {"Odkryta 45, Warszawa 03-140",                        "Modlińska 257, Warszawa 03-152"},
        {"Przyczółkowa 27, Warszawa 02-962",                   "Klimczaka 14, Warszawa 02-797"},
        // CROSS-DISTRICT MEDIUM RIDES (11-20)
        {"Aleje Jerozolimskie 44, Warszawa 00-024",            "Ursynów, Warszawa"},
        {"Dworzec Centralny, Warszawa",                        "Puławska 145, Warszawa 02-715"},
        {"Złote Tarasy, Warszawa",                             "Targowa 74, Warszawa 03-734"},
        {"Wolska 80, Warszawa 01-140",                         "Grochowska 207, Warszawa 04-067"},
        {"Mickiewicza 27, Warszawa 01-517",                    "Rosoła 18, Warszawa 02-786"},
        {"Lazurowa 14, Warszawa 01-315",                       "Puławska 145, Warszawa 02-715"},
        {"Marszałkowska 140, Warszawa 00-061",                 "Odkryta 45, Warszawa 03-140"},
        {"Woronicza 17, Warszawa 02-640",                      "Ząbkowska 23, Warszawa 03-736"},
        {"Komisji Edukacji Narodowej 36, Warszawa 02-768",     "Wolska 80, Warszawa 01-140"},
        {"Klimczaka 14, Warszawa 02-797",                      "Modlińska 257, Warszawa 03-152"},
        // CROSS-RIVER LONG RIDES (21-30)
        {"Lotnisko Chopina, Warszawa",                         "Grochowska 207, Warszawa 04-067"},
        {"Złota 44, Warszawa 00-120",                          "Plac Przymierza 4, Warszawa 03-944"},
        {"Wolska 80, Warszawa 01-140",                         "Kamionek 11, Warszawa 03-812"},
        {"Metro Bemowo, Bemowo, Warszawa",                     "Targowa 74, Warszawa 03-734"},
        {"Lazurowa 14, Warszawa 01-315",                       "Ząbkowska 23, Warszawa 03-736"},
        {"Puławska 145, Warszawa 02-715",                      "Modlińska 257, Warszawa 03-152"},
        {"Klimczaka 14, Warszawa 02-797",                      "Odkryta 45, Warszawa 03-140"},
        {"Baseny Inflancka 02 - Bus Stop, Westfield Arkadia",  "Ulica Siennicka 50, Warszawa 04-393"},
        {"Ulica Urocza 5, Warszawa 04-651",                    "Bollywood Lounge Restaurant, Bar & Sheesha Bar, Nowogrodzka, Warsaw, Poland"},
        {"Warszawa Zachodnia Bus Station, Warsaw West Railway Station", "Plac Przymierza 4, Warszawa 03-944"},
    };

    private static boolean withinWarsaw(double lat, double lon) {
        return lat >= LAT_MIN && lat <= LAT_MAX && lon >= LON_MIN && lon <= LON_MAX;
    }

    private static boolean isExactFallback(double lat, double lon) {
        return lat == CENTRE_LAT && lon == CENTRE_LON;
    }

    private static boolean passes(double lat, double lon) {
        return withinWarsaw(lat, lon) && !isExactFallback(lat, lon);
    }

    @Test
    public void testAllPairs() throws Exception {
        System.out.println("\n=== GeocoderTest Run ===\n");
        boolean allPassed = true;
        StringBuilder sb = new StringBuilder("=== GeocoderTest Run ===\n\n");

        for (int i = 0; i < PAIRS.length; i++) {
            String pickupAddr  = PAIRS[i][0];
            String dropoffAddr = PAIRS[i][1];

            double[] p = AddressGeocoder.geocode(pickupAddr);
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            double[] d = AddressGeocoder.geocode(dropoffAddr);
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}

            boolean pPass = passes(p[0], p[1]);
            boolean dPass = passes(d[0], d[1]);
            boolean rowPass = pPass && dPass;
            if (!rowPass) allPassed = false;

            String line = String.format(
                "[%d] PICKUP lat=%.6f lon=%.6f %s | DROPOFF lat=%.6f lon=%.6f %s%n",
                i + 1, p[0], p[1], pPass ? "PASS" : "FAIL",
                       d[0], d[1], dPass ? "PASS" : "FAIL");
            String detail = String.format(
                "    P: %s%n    D: %s%n", pickupAddr, dropoffAddr);

            System.out.print(line);
            System.out.print(detail);
            sb.append(line).append(detail);
        }

        String summary = "\n" + (allPassed ? "ALL TESTS PASSED" : "SOME TESTS FAILED") + "\n";
        System.out.print(summary);
        sb.append(summary);

        try {
            String outPath = System.getProperty("user.dir") + "/geocoder_test_results.txt";
            Files.write(Paths.get(outPath), sb.toString().getBytes(StandardCharsets.UTF_8));
            System.out.println("Results written to " + outPath);
        } catch (Exception e) {
            System.err.println("Could not write results: " + e.getMessage());
        }

        assertTrue("One or more geocoding assertions failed — see console output above", allPassed);
    }

    @Test
    public void testSpecificAddresses() throws Exception {
        // "Ulica Urocza 5, Warszawa 04-651" must resolve east of 21.05 (Wawer district)
        double[] urocza = AddressGeocoder.geocode("Ulica Urocza 5, Warszawa 04-651");
        assertTrue("Urocza lon should be > 21.05, got " + urocza[1], urocza[1] > 21.05);

        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}

        // "Aleja Jana Pawła II 41A, Warszawa" must be non-null and not the fallback centre
        double[] jpii = AddressGeocoder.geocode("Aleja Jana Pawła II 41A, Warszawa");
        boolean notCentre = !(jpii[0] == CENTRE_LAT && jpii[1] == CENTRE_LON);
        boolean inBounds  = jpii[0] >= LAT_MIN && jpii[0] <= LAT_MAX
                         && jpii[1] >= LON_MIN && jpii[1] <= LON_MAX;
        assertTrue("Jana Pawła II should not be fallback centre", notCentre);
        assertTrue("Jana Pawła II should be within Warsaw bounds, got "
                   + jpii[0] + "," + jpii[1], inBounds);
    }
}
