package com.boltscout;

import java.util.Calendar;

/**
 * Produces TAKE_IT / SKIP / YOUR_CALL verdicts for a Warsaw Bolt ride.
 *
 * Rules (straight-line distance, Warsaw city centre as anchor):
 * ─────────────────────────────────────────────────────────────
 * Peak hours  : 07:00–10:00  (morning rush)  and  15:00–19:00  (evening rush)
 * Peak limit  : 8 km  →  trips longer than this are risky (traffic + dead zone)
 *
 * Morning peak logic:
 *   Traffic flows suburb → centre.  Trips into centre = slow + no fares at end.
 *   Prefer short trips or trips going with traffic (away from centre).
 *
 * Evening peak logic:
 *   Traffic flows centre → suburb.  Trips out of city = slow + stranded.
 *   Prefer short trips or trips staying in/near centre.
 *
 * Off-peak:
 *   Relaxed rules.  Any trip ≤ 12 km is fine; beyond that YOUR_CALL.
 */
public class VerdictEngine {

    public enum Verdict { TAKE_IT, SKIP, YOUR_CALL }

    // Warsaw city centre
    private static final double CENTRE_LAT = 52.2297;
    private static final double CENTRE_LON = 21.0122;

    // Thresholds (km, straight-line)
    private static final double PEAK_SKIP_KM      = 8.0;
    private static final double PEAK_CAUTION_KM   = 5.0;
    private static final double OFFPEAK_SKIP_KM   = 12.0;
    private static final double OFFPEAK_CAUTION_KM = 9.0;

    public static class Result {
        public final Verdict verdict;
        public final String  reason;
        public final double  distanceKm;
        public final boolean isPeak;
        public final boolean isMorningPeak;
        public final String  directionLabel;

        Result(Verdict verdict, String reason, double distanceKm,
               boolean isPeak, boolean isMorningPeak, String directionLabel) {
            this.verdict        = verdict;
            this.reason         = reason;
            this.distanceKm     = distanceKm;
            this.isPeak         = isPeak;
            this.isMorningPeak  = isMorningPeak;
            this.directionLabel = directionLabel;
        }
    }

    public static Result evaluate(RideRequest ride) {
        double dist = haversineKm(
                ride.pickupLat, ride.pickupLon,
                ride.dropoffLat, ride.dropoffLon);

        Calendar cal  = Calendar.getInstance();
        int hour      = cal.get(Calendar.HOUR_OF_DAY);
        boolean isMorningPeak  = hour >= 7  && hour < 10;
        boolean isEveningPeak  = hour >= 15 && hour < 19;
        boolean isPeak         = isMorningPeak || isEveningPeak;

        // Distance of dropoff from city centre
        double dropoffFromCentre = haversineKm(
                ride.dropoffLat, ride.dropoffLon, CENTRE_LAT, CENTRE_LON);
        double pickupFromCentre  = haversineKm(
                ride.pickupLat, ride.pickupLon, CENTRE_LAT, CENTRE_LON);

        boolean goingTowardCentre = dropoffFromCentre < pickupFromCentre - 0.5;
        boolean goingAwayFromCentre = dropoffFromCentre > pickupFromCentre + 0.5;
        boolean dropoffIsCentral = dropoffFromCentre < 4.0;
        boolean dropoffIsSuburb  = dropoffFromCentre > 9.0;

        String dirLabel;
        if (goingTowardCentre)       dirLabel = "→ Centre";
        else if (goingAwayFromCentre) dirLabel = "→ Suburbs";
        else                         dirLabel = "Lateral";

        if (!ride.hasCoordinates()) {
            return new Result(Verdict.YOUR_CALL,
                    "Addresses not geocoded — check map manually",
                    dist, isPeak, isMorningPeak, dirLabel);
        }

        if (isPeak) {
            // --- PEAK HOUR RULES ---
            if (dist > PEAK_SKIP_KM) {
                if (dropoffIsSuburb) {
                    return new Result(Verdict.SKIP,
                            String.format("%.1f km to suburbs during peak — you'll be stranded", dist),
                            dist, true, isMorningPeak, dirLabel);
                }
                return new Result(Verdict.YOUR_CALL,
                        String.format("%.1f km during peak — long but dropoff near centre", dist),
                        dist, true, isMorningPeak, dirLabel);
            }

            if (dist <= 3.0) {
                return new Result(Verdict.TAKE_IT,
                        String.format("%.1f km short hop during peak — quick money", dist),
                        dist, true, isMorningPeak, dirLabel);
            }

            if (isMorningPeak && goingTowardCentre && dist > PEAK_CAUTION_KM) {
                return new Result(Verdict.YOUR_CALL,
                        "Into centre during morning rush — heavy traffic likely",
                        dist, true, true, dirLabel);
            }

            if (isEveningPeak && goingAwayFromCentre && dist > PEAK_CAUTION_KM) {
                return new Result(Verdict.YOUR_CALL,
                        "Out of centre during evening rush — will be slow",
                        dist, true, false, dirLabel);
            }

            if (dropoffIsCentral) {
                return new Result(Verdict.TAKE_IT,
                        String.format("%.1f km, ends in centre — easy next ride", dist),
                        dist, true, isMorningPeak, dirLabel);
            }

            return new Result(Verdict.YOUR_CALL,
                    String.format("%.1f km during peak — moderate", dist),
                    dist, true, isMorningPeak, dirLabel);

        } else {
            // --- OFF-PEAK RULES ---
            if (dist > OFFPEAK_SKIP_KM && dropoffIsSuburb) {
                return new Result(Verdict.SKIP,
                        String.format("%.1f km to suburbs off-peak — no fares out there", dist),
                        dist, false, false, dirLabel);
            }

            if (dist > OFFPEAK_CAUTION_KM) {
                return new Result(Verdict.YOUR_CALL,
                        String.format("%.1f km off-peak — decent fare but long repositioning", dist),
                        dist, false, false, dirLabel);
            }

            return new Result(Verdict.TAKE_IT,
                    String.format("%.1f km off-peak — good ride", dist),
                    dist, false, false, dirLabel);
        }
    }

    /** Haversine great-circle distance in kilometres. */
    public static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
