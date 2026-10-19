package com.tempoagora;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Normalized Open-Meteo response for one location. Missing numeric fields are NaN;
 * missing weather codes are -1.
 */
public final class WeatherSnapshot {
    private static final long CURRENT_FRESH_MILLIS = 60 * 60_000L;

    public final Place place;
    public final String timezone;
    public final int utcOffsetSeconds;
    /** Millis do aparelho quando a resposta foi buscada; 0 se desconhecido (cache antigo). */
    public final long fetchedAt;
    public final String currentTime;
    public final double currentTemperature;
    public final double currentFeelsLike;
    public final double currentHumidity;
    public final double currentPrecipitation;
    public final int currentWeatherCode;
    public final boolean currentIsDay;
    public final double currentWindSpeed;
    public final double currentWindDirection;
    public final double currentWindGusts;
    public final List<Hourly> hourly;
    public final List<Daily> daily;
    private final List<String> hourlyTimes;
    private final JSONObject raw;

    private WeatherSnapshot(Place place, JSONObject json) {
        this.timezone = textOr(json, "timezone", place.timezone);
        this.place = place.withTimezone(timezone);
        this.utcOffsetSeconds = json.optInt("utc_offset_seconds", 0);
        this.fetchedAt = json.optLong("_fetched_at", 0);
        this.raw = json;

        JSONObject current = json.optJSONObject("current");
        if (current == null) current = new JSONObject();
        this.currentTime = textOr(current, "time", "");
        this.currentTemperature = number(current, "temperature_2m");
        this.currentFeelsLike = number(current, "apparent_temperature");
        this.currentHumidity = number(current, "relative_humidity_2m");
        this.currentPrecipitation = number(current, "precipitation");
        this.currentWeatherCode = code(number(current, "weather_code"));
        this.currentIsDay = current.optInt("is_day", 1) == 1;
        this.currentWindSpeed = number(current, "wind_speed_10m");
        this.currentWindDirection = number(current, "wind_direction_10m");
        this.currentWindGusts = number(current, "wind_gusts_10m");

        List<Hourly> hours = new ArrayList<>();
        JSONObject hourlyObject = json.optJSONObject("hourly");
        if (hourlyObject != null) {
            JSONArray times = hourlyObject.optJSONArray("time");
            int count = times == null ? 0 : times.length();
            for (int i = 0; i < count; i++) {
                hours.add(new Hourly(textAt(hourlyObject, "time", i),
                        numberAt(hourlyObject, "temperature_2m", i),
                        numberAt(hourlyObject, "apparent_temperature", i),
                        numberAt(hourlyObject, "relative_humidity_2m", i),
                        numberAt(hourlyObject, "precipitation", i),
                        numberAt(hourlyObject, "precipitation_probability", i),
                        code(numberAt(hourlyObject, "weather_code", i)),
                        numberAt(hourlyObject, "wind_speed_10m", i),
                        numberAt(hourlyObject, "wind_direction_10m", i),
                        flagAt(hourlyObject, "is_day", i)));
            }
        }
        this.hourly = Collections.unmodifiableList(hours);
        List<String> timeList = new ArrayList<>(hours.size());
        for (Hourly hour : hours) timeList.add(hour.time);
        this.hourlyTimes = Collections.unmodifiableList(timeList);

        List<Daily> days = new ArrayList<>();
        JSONObject dailyObject = json.optJSONObject("daily");
        if (dailyObject != null) {
            JSONArray dates = dailyObject.optJSONArray("time");
            int count = dates == null ? 0 : dates.length();
            for (int i = 0; i < count; i++) {
                days.add(new Daily(textAt(dailyObject, "time", i),
                        numberAt(dailyObject, "temperature_2m_min", i),
                        numberAt(dailyObject, "temperature_2m_max", i),
                        numberAt(dailyObject, "apparent_temperature_min", i),
                        numberAt(dailyObject, "apparent_temperature_max", i),
                        numberAt(dailyObject, "relative_humidity_2m_mean", i),
                        numberAt(dailyObject, "precipitation_sum", i),
                        numberAt(dailyObject, "precipitation_probability_max", i),
                        code(numberAt(dailyObject, "weather_code", i)),
                        textAt(dailyObject, "sunrise", i),
                        textAt(dailyObject, "sunset", i),
                        numberAt(dailyObject, "uv_index_max", i),
                        numberAt(dailyObject, "wind_speed_10m_max", i)));
            }
        }
        this.daily = Collections.unmodifiableList(days);
    }

    public static WeatherSnapshot fromJson(JSONObject json, Place place) {
        return new WeatherSnapshot(place, json);
    }

    public JSONObject toJson() {
        return raw;
    }

    /** Hora atual no fuso do lugar, "yyyy-MM-ddTHH:mm". */
    public String localNow() {
        return ForecastTime.localNow(timezone, utcOffsetSeconds, System.currentTimeMillis());
    }

    /** Data de hoje no fuso do lugar, "yyyy-MM-dd". */
    public String localToday() {
        String now = localNow();
        return now.length() >= 10 ? now.substring(0, 10) : now;
    }

    public List<String> hourlyTimes() {
        return hourlyTimes;
    }

    /** Dias a partir de hoje (corrige cache de dias passados). */
    public List<Daily> upcomingDaily() {
        String today = localToday();
        List<Daily> out = new ArrayList<>();
        for (Daily day : daily) {
            if (day.date.compareTo(today) >= 0) out.add(day);
        }
        return out;
    }

    /**
     * Condições para o agora. Usa o bloco "current" se foi buscado na última hora;
     * senão usa a previsão da hora local atual (fromForecast = true).
     */
    public Now now(long nowMillis) {
        boolean fresh = fetchedAt > 0 && nowMillis - fetchedAt < CURRENT_FRESH_MILLIS;
        if (fresh && !Double.isNaN(currentTemperature)) return currentNow();
        String localNow = ForecastTime.localNow(timezone, utcOffsetSeconds, nowMillis);
        int index = ForecastTime.indexOfHour(hourlyTimes, localNow);
        if (index >= 0) return hourNow(hourly.get(index));
        return currentNow();
    }

    private Now currentNow() {
        return new Now(currentTemperature, currentFeelsLike, currentHumidity, currentPrecipitation,
                currentWindSpeed, currentWindDirection, currentWeatherCode, currentIsDay, false);
    }

    private static Now hourNow(Hourly hour) {
        return new Now(hour.temperature, hour.feelsLike, hour.humidity, hour.precipitation,
                hour.windSpeed, hour.windDirection, hour.weatherCode, hour.isDay, true);
    }

    public static final class Hourly {
        public final String time;
        public final double temperature;
        public final double feelsLike;
        public final double humidity;
        public final double precipitation;
        public final double precipitationProbability;
        public final int weatherCode;
        public final double windSpeed;
        public final double windDirection;
        public final boolean isDay;

        Hourly(String time, double temperature, double feelsLike, double humidity,
               double precipitation, double precipitationProbability, int weatherCode,
               double windSpeed, double windDirection, boolean isDay) {
            this.time = time; this.temperature = temperature; this.feelsLike = feelsLike;
            this.humidity = humidity; this.precipitation = precipitation;
            this.precipitationProbability = precipitationProbability; this.weatherCode = weatherCode;
            this.windSpeed = windSpeed; this.windDirection = windDirection; this.isDay = isDay;
        }
    }

    public static final class Daily {
        public final String date;
        public final double minTemperature;
        public final double maxTemperature;
        public final double minFeelsLike;
        public final double maxFeelsLike;
        public final double meanHumidity;
        public final double precipitation;
        public final double precipitationProbability;
        public final int weatherCode;
        /** Horários ISO locais ("yyyy-MM-ddTHH:mm"), ou "" se ausentes. */
        public final String sunrise;
        public final String sunset;
        public final double uvIndexMax;
        public final double windSpeedMax;

        Daily(String date, double minTemperature, double maxTemperature, double minFeelsLike,
              double maxFeelsLike, double meanHumidity, double precipitation,
              double precipitationProbability, int weatherCode, String sunrise, String sunset,
              double uvIndexMax, double windSpeedMax) {
            this.date = date; this.minTemperature = minTemperature; this.maxTemperature = maxTemperature;
            this.minFeelsLike = minFeelsLike; this.maxFeelsLike = maxFeelsLike;
            this.meanHumidity = meanHumidity; this.precipitation = precipitation;
            this.precipitationProbability = precipitationProbability; this.weatherCode = weatherCode;
            this.sunrise = sunrise; this.sunset = sunset;
            this.uvIndexMax = uvIndexMax; this.windSpeedMax = windSpeedMax;
        }
    }

    /** Condições para o cartão "agora". */
    public static final class Now {
        public final double temperature;
        public final double feelsLike;
        public final double humidity;
        public final double precipitation;
        public final double windSpeed;
        public final double windDirection;
        public final int weatherCode;
        public final boolean isDay;
        /** true quando veio da previsão horária (dado pode não ser de agora). */
        public final boolean fromForecast;

        private Now(double temperature, double feelsLike, double humidity, double precipitation,
                    double windSpeed, double windDirection, int weatherCode, boolean isDay,
                    boolean fromForecast) {
            this.temperature = temperature; this.feelsLike = feelsLike; this.humidity = humidity;
            this.precipitation = precipitation; this.windSpeed = windSpeed;
            this.windDirection = windDirection; this.weatherCode = weatherCode;
            this.isDay = isDay; this.fromForecast = fromForecast;
        }
    }

    private static int code(double value) {
        return Double.isNaN(value) || Double.isInfinite(value) ? -1 : (int) value;
    }

    private static String textOr(JSONObject json, String key, String fallback) {
        if (json.isNull(key)) return fallback;
        String value = json.optString(key, "");
        return value.isEmpty() ? fallback : value;
    }

    private static double number(JSONObject json, String key) {
        Object value = json.opt(key);
        if (!(value instanceof Number)) return Double.NaN;
        return ((Number) value).doubleValue();
    }

    private static double numberAt(JSONObject json, String key, int index) {
        JSONArray values = json.optJSONArray(key);
        if (values == null || index >= values.length() || values.isNull(index)) return Double.NaN;
        Object value = values.opt(index);
        return value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
    }

    /** Sem o campo, considera "é dia" (padrão seguro para a tela). */
    private static boolean flagAt(JSONObject json, String key, int index) {
        double value = numberAt(json, key, index);
        return Double.isNaN(value) || value == 1;
    }

    private static String textAt(JSONObject json, String key, int index) {
        JSONArray values = json.optJSONArray(key);
        if (values == null || index >= values.length() || values.isNull(index)) return "";
        return values.optString(index, "");
    }
}
