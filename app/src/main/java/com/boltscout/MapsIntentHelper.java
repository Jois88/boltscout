package com.boltscout;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

public class MapsIntentHelper {

    public static void openDirections(Context context, String pickup, String dropoff) {
        String origin = clean(pickup);
        String dest   = clean(dropoff);

        Uri uri = Uri.parse("https://www.google.com/maps/dir/"
                + Uri.encode(origin) + "/" + Uri.encode(dest));

        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        intent.setPackage("com.google.android.apps.maps");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        if (context.getPackageManager().resolveActivity(intent, 0) == null) {
            intent.setPackage(null);
        }
        context.startActivity(intent);
    }

    static String clean(String address) {
        if (address == null || address.trim().isEmpty()) return "Warszawa";
        String s = address.trim();
        // Strip "- Bus Stop" / "Bus Stop" variants (case-insensitive)
        s = s.replaceAll("(?i)\\s*-?\\s*bus\\s+stop", "").trim();
        // Strip any trailing comma/whitespace left behind
        s = s.replaceAll("[,\\s]+$", "").trim();
        // Append city if neither form is already present
        if (!s.toLowerCase().contains("warszawa") && !s.toLowerCase().contains("warsaw")) {
            s = s + ", Warszawa";
        }
        return s;
    }
}
