package com.tempoagora;

import org.json.JSONObject;
import java.util.Locale;

/** A named point on the map that can be searched, selected, or favorited. */
public final class Place {
    public final String name;
    public final String admin1;
    public final String country;
    public final String countryCode;
    public final double latitude;
    public final double longitude;
    public final String timezone;
    public final boolean currentLocation;

    public Place(String name, String admin1, String country, String countryCode,
                 double latitude, double longitude, String timezone, boolean currentLocation) {
        this.name = clean(name, "Local escolhido");
        this.admin1 = clean(admin1, "");
        this.country = clean(country, "");
        this.countryCode = clean(countryCode, "");
        this.latitude = latitude;
        this.longitude = longitude;
        this.timezone = clean(timezone, "auto");
        this.currentLocation = currentLocation;
    }

    public String key() {
        return String.format(Locale.US, "%.5f,%.5f", latitude, longitude);
    }

    public String subtitle() {
        StringBuilder out = new StringBuilder();
        if (!admin1.isEmpty() && !admin1.equalsIgnoreCase(name)) out.append(admin1);
        if (!country.isEmpty() && !country.equalsIgnoreCase(name)) {
            if (out.length() > 0) out.append(" · ");
            out.append(country);
        }
        return out.length() == 0 ? String.format(Locale.US, "%.3f, %.3f", latitude, longitude) : out.toString();
    }

    public Place asCurrentLocation() {
        return new Place(name, admin1, country, countryCode, latitude, longitude, timezone, true);
    }

    public Place withTimezone(String value) {
        return new Place(name, admin1, country, countryCode, latitude, longitude, value, currentLocation);
    }

    public JSONObject toJson() {
        JSONObject out = new JSONObject();
        try {
            out.put("name", name);
            out.put("admin1", admin1);
            out.put("country", country);
            out.put("countryCode", countryCode);
            out.put("latitude", latitude);
            out.put("longitude", longitude);
            out.put("timezone", timezone);
            out.put("currentLocation", currentLocation);
        } catch (Exception ignored) { }
        return out;
    }

    /** Lugar do aparelho: coordenadas com 2 casas (~1 km), chave estável e mais privacidade. */
    public static Place device(String name, String admin1, String country, double latitude, double longitude) {
        return new Place(name, admin1, country, "", round2(latitude), round2(longitude), "auto", true);
    }

    public boolean sameKey(Place other) {
        return other != null && key().equals(other.key());
    }

    /** Devolve null se faltar latitude/longitude ou se forem inválidas. */
    public static Place fromJson(JSONObject json) {
        if (json == null) return null;
        double latitude = json.optDouble("latitude", Double.NaN);
        double longitude = json.optDouble("longitude", Double.NaN);
        if (!validCoordinates(latitude, longitude)) return null;
        return new Place(json.optString("name", "Local escolhido"),
                json.optString("admin1", ""), json.optString("country", ""),
                json.optString("countryCode", ""), latitude, longitude,
                json.optString("timezone", "auto"), json.optBoolean("currentLocation", false));
    }

    static boolean validCoordinates(double latitude, double longitude) {
        return !Double.isNaN(latitude) && !Double.isInfinite(latitude) && latitude >= -90 && latitude <= 90
                && !Double.isNaN(longitude) && !Double.isInfinite(longitude) && longitude >= -180 && longitude <= 180;
    }

    private static double round2(double value) {
        return Math.round(value * 100) / 100.0;
    }

    private static String clean(String value, String fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }
}
