package com.tempoagora;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Small keyless client for the Open-Meteo forecast and geocoding APIs. */
public final class WeatherApiClient {
    private static final String FORECAST_URL = "https://api.open-meteo.com/v1/forecast";
    private static final String GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search";
    private static final String CURRENT = "temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code";
    private static final String HOURLY = "temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,precipitation_probability,weather_code";
    private static final String DAILY = "temperature_2m_min,temperature_2m_max,apparent_temperature_min,apparent_temperature_max,relative_humidity_2m_mean,precipitation_sum,precipitation_probability_max,weather_code";

    public List<WeatherSnapshot> fetch(List<Place> places) throws Exception {
        if (places == null || places.isEmpty()) return new ArrayList<>();
        StringBuilder latitudes = new StringBuilder();
        StringBuilder longitudes = new StringBuilder();
        for (Place place : places) {
            if (latitudes.length() > 0) { latitudes.append(','); longitudes.append(','); }
            latitudes.append(place.latitude);
            longitudes.append(place.longitude);
        }
        String query = "latitude=" + encode(latitudes.toString())
                + "&longitude=" + encode(longitudes.toString())
                + "&current=" + encode(CURRENT)
                + "&hourly=" + encode(HOURLY)
                + "&daily=" + encode(DAILY)
                + "&forecast_days=15&timezone=auto&temperature_unit=celsius&precipitation_unit=mm";
        String response = get(FORECAST_URL + "?" + query);
        List<JSONObject> objects = new ArrayList<>();
        String trimmed = response.trim();
        if (trimmed.startsWith("[")) {
            JSONArray array = new JSONArray(trimmed);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item != null) objects.add(item);
            }
        } else {
            objects.add(new JSONObject(trimmed));
        }
        if (objects.size() != places.size()) {
            throw new Exception("A API retornou " + objects.size() + " locais para " + places.size() + " solicitados.");
        }
        List<WeatherSnapshot> result = new ArrayList<>();
        for (int i = 0; i < objects.size(); i++) result.add(WeatherSnapshot.fromJson(objects.get(i), places.get(i)));
        return result;
    }

    public List<Place> search(String searchTerm) throws Exception {
        String term = searchTerm == null ? "" : searchTerm.trim();
        if (term.length() < 2) return new ArrayList<>();
        String response = get(GEOCODING_URL + "?name=" + encode(term) + "&count=10&language=pt&format=json");
        JSONObject root = new JSONObject(response);
        JSONArray results = root.optJSONArray("results");
        List<Place> places = new ArrayList<>();
        if (results == null) return places;
        for (int i = 0; i < results.length(); i++) {
            JSONObject item = results.optJSONObject(i);
            if (item == null || !item.has("latitude") || !item.has("longitude")) continue;
            places.add(new Place(item.optString("name", term), item.optString("admin1", ""),
                    item.optString("country", ""), item.optString("country_code", ""),
                    item.optDouble("latitude"), item.optDouble("longitude"),
                    item.optString("timezone", "auto"), false));
        }
        return places;
    }

    private static String get(String address) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(15000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "TempoAgora/1.0 (Android)");
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            StringBuilder body = new StringBuilder();
            if (stream != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) body.append(line);
                }
            }
            if (status < 200 || status >= 300) {
                String reason = body.toString();
                if (reason.length() > 180) reason = reason.substring(0, 180);
                throw new Exception("HTTP " + status + (reason.isEmpty() ? "" : ": " + reason));
            }
            return body.toString();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }
}
