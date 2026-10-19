package com.tempoagora;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Small keyless client for the Open-Meteo forecast and geocoding APIs. Call it off the UI thread. */
public final class WeatherApiClient {
    private static final String FORECAST_URL = "https://api.open-meteo.com/v1/forecast";
    private static final String GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search";
    private static final String USER_AGENT = "Atemporal/1.1 (Android)";
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final String CURRENT = "temperature_2m,relative_humidity_2m,apparent_temperature,is_day,precipitation,weather_code,wind_speed_10m,wind_direction_10m,wind_gusts_10m";
    private static final String HOURLY = "temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,precipitation_probability,weather_code,wind_speed_10m,wind_direction_10m,is_day";
    private static final String DAILY = "temperature_2m_min,temperature_2m_max,apparent_temperature_min,apparent_temperature_max,relative_humidity_2m_mean,precipitation_sum,precipitation_probability_max,weather_code,sunrise,sunset,uv_index_max,wind_speed_10m_max";

    /** Falha de rede ou da API, com uma mensagem pronta para o usuário. */
    public static final class ApiException extends Exception {
        public enum Kind { NETWORK, TIMEOUT, RATE_LIMIT, SERVER, REJECTED, PARSE }

        public final Kind kind;

        ApiException(Kind kind, String detail, Throwable cause) {
            super(detail, cause);
            this.kind = kind;
        }

        public String userMessage() {
            switch (kind) {
                case TIMEOUT: return "O serviço de clima demorou para responder.";
                case RATE_LIMIT: return "Muitas consultas seguidas. Tente de novo em alguns minutos.";
                case SERVER: return "O serviço de clima está indisponível no momento.";
                case REJECTED: return "A consulta foi recusada pelo serviço de clima.";
                case PARSE: return "Resposta inesperada do serviço de clima.";
                default: return "Sem conexão com a internet.";
            }
        }
    }

    /** Busca a previsão de vários lugares em uma requisição. Sempre em métrico. */
    public List<WeatherSnapshot> fetch(List<Place> places) throws ApiException {
        List<WeatherSnapshot> result = new ArrayList<>();
        if (places == null || places.isEmpty()) return result;
        StringBuilder latitudes = new StringBuilder();
        StringBuilder longitudes = new StringBuilder();
        for (Place place : places) {
            if (latitudes.length() > 0) { latitudes.append(','); longitudes.append(','); }
            latitudes.append(String.format(Locale.US, "%.4f", place.latitude));
            longitudes.append(String.format(Locale.US, "%.4f", place.longitude));
        }
        String query = "latitude=" + encode(latitudes.toString())
                + "&longitude=" + encode(longitudes.toString())
                + "&current=" + encode(CURRENT)
                + "&hourly=" + encode(HOURLY)
                + "&daily=" + encode(DAILY)
                + "&forecast_days=15&timezone=auto&temperature_unit=celsius"
                + "&precipitation_unit=mm&wind_speed_unit=kmh";
        String response = get(FORECAST_URL + "?" + query);
        long fetchedAt = System.currentTimeMillis();
        try {
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
                throw new ApiException(ApiException.Kind.PARSE,
                        "A API retornou " + objects.size() + " locais para " + places.size(), null);
            }
            for (int i = 0; i < objects.size(); i++) {
                JSONObject item = objects.get(i);
                item.put("_fetched_at", fetchedAt);
                result.add(WeatherSnapshot.fromJson(item, places.get(i)));
            }
        } catch (JSONException error) {
            throw new ApiException(ApiException.Kind.PARSE, error.toString(), error);
        }
        return result;
    }

    /** Busca cidades. Termos com menos de 2 caracteres devolvem lista vazia. */
    public List<Place> search(String searchTerm) throws ApiException {
        String term = searchTerm == null ? "" : searchTerm.trim();
        List<Place> places = new ArrayList<>();
        if (term.length() < 2) return places;
        String response = get(GEOCODING_URL + "?name=" + encode(term) + "&count=10&language=pt&format=json");
        try {
            JSONObject root = new JSONObject(response);
            JSONArray results = root.optJSONArray("results");
            if (results == null) return places;
            for (int i = 0; i < results.length(); i++) {
                JSONObject item = results.optJSONObject(i);
                if (item == null) continue;
                double latitude = item.optDouble("latitude", Double.NaN);
                double longitude = item.optDouble("longitude", Double.NaN);
                if (!Place.validCoordinates(latitude, longitude)) continue;
                places.add(new Place(item.optString("name", term), item.optString("admin1", ""),
                        item.optString("country", ""), item.optString("country_code", ""),
                        latitude, longitude, item.optString("timezone", "auto"), false));
            }
            return places;
        } catch (JSONException error) {
            throw new ApiException(ApiException.Kind.PARSE, error.toString(), error);
        }
    }

    private static String get(String address) throws ApiException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String body = readAll(stream);
            if (status == 429) throw new ApiException(ApiException.Kind.RATE_LIMIT, "HTTP 429", null);
            if (status >= 500) throw new ApiException(ApiException.Kind.SERVER, "HTTP " + status, null);
            if (status < 200 || status >= 300) {
                String reason = body.length() > 180 ? body.substring(0, 180) : body;
                throw new ApiException(ApiException.Kind.REJECTED, "HTTP " + status + ": " + reason, null);
            }
            return body;
        } catch (SocketTimeoutException timeout) {
            throw new ApiException(ApiException.Kind.TIMEOUT, timeout.toString(), timeout);
        } catch (IOException network) {
            throw new ApiException(ApiException.Kind.NETWORK, network.toString(), network);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readAll(InputStream stream) throws IOException {
        StringBuilder body = new StringBuilder();
        if (stream == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
        }
        return body.toString();
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            return value;
        }
    }
}
