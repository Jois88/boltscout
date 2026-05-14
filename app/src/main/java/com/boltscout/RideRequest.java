package com.boltscout;

/**
 * Immutable value object representing a detected Bolt ride request.
 * Carries raw address strings plus geocoded lat/lon for both endpoints.
 */
public final class RideRequest {

    public static final RideRequest EMPTY =
            new RideRequest("", "", 0, 0, 0, 0);

    public final String pickupAddress;
    public final String dropoffAddress;
    public final double pickupLat;
    public final double pickupLon;
    public final double dropoffLat;
    public final double dropoffLon;

    public RideRequest(String pickupAddress, String dropoffAddress,
                       double pickupLat, double pickupLon,
                       double dropoffLat, double dropoffLon) {
        this.pickupAddress  = pickupAddress  != null ? pickupAddress.trim()  : "";
        this.dropoffAddress = dropoffAddress != null ? dropoffAddress.trim() : "";
        this.pickupLat  = pickupLat;
        this.pickupLon  = pickupLon;
        this.dropoffLat = dropoffLat;
        this.dropoffLon = dropoffLon;
    }

    public boolean hasCoordinates() {
        return pickupLat != 0 && pickupLon != 0 && dropoffLat != 0 && dropoffLon != 0;
    }

    public boolean isEmpty() {
        return pickupAddress.isEmpty() && dropoffAddress.isEmpty();
    }
}
