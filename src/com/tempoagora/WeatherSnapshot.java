package com.tempoagora;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Normalized Open-Meteo response for one location. Missing API fields are NaN. */
public final class WeatherSnapshot {
    public final Place place;
    public final String timezone;
    public final String currentTime;
    public final double currentTemperature;
    public final double currentFeelsLike;
    public final double currentHumidity;
    public final double currentPrecipitation;
    public final int currentWeatherCode;
    public final List<Hourly> hourly;
    public final List<Daily> daily;
    private final JSONObject raw;

    private WeatherSnapshot(Place place, String timezone, String currentTime,
                            double currentTemperature, double currentFeelsLike,
                            double currentHumidity, double currentPrecipitation,
                            int currentWeatherCode, List<Hourly> hourly, List<Daily> daily,
                            JSONObject raw) {
        this.place = place.withTimezone(timezone);
        this.timezone = timezone;
        this.currentTime = currentTime;
        this.currentTemperature = currentTemperature;
        this.currentFeelsLike = currentFeelsLike;
        this.currentHumidity = currentHumidity;
        this.currentPrecipitation = currentPrecipitation;
        this.currentWeatherCode = currentWeatherCode;
        this.hourly = hourly;
        this.daily = daily;
        this.raw = raw;
    }

    public static WeatherSnapshot fromJson(JSONObject json, Place place) {
        String timezone = json.optString("timezone", place.timezone);
        JSONObject current = json.optJSONObject("current");
        if (current == null) current = new JSONObject();
        List<Hourly> hours = new ArrayList<>();
        JSONObject hourlyObject = json.optJSONObject("hourly");
        if (hourlyObject != null) {
            JSONArray times = hourlyObject.optJSONArray("time");
            if (times != null) {
                for (int i = 0; i < times.length(); i++) {
                    hours.add(new Hourly(stringAt(times, i), numberAt(hourlyObject, "temperature_2m", i),
                            numberAt(hourlyObject, "apparent_temperature", i),
                            numberAt(hourlyObject, "relative_humidity_2m", i),
                            numberAt(hourlyObject, "precipitation", i),
                            numberAt(hourlyObject, "precipitation_probability", i),
                            (int) numberAt(hourlyObject, "weather_code", i)));
                }
            }
        }
        List<Daily> days = new ArrayList<>();
        JSONObject dailyObject = json.optJSONObject("daily");
        if (dailyObject != null) {
            JSONArray dates = dailyObject.optJSONArray("time");
            if (dates != null) {
                for (int i = 0; i < dates.length(); i++) {
                    days.add(new Daily(stringAt(dates, i),
                            numberAt(dailyObject, "temperature_2m_min", i),
                            numberAt(dailyObject, "temperature_2m_max", i),
                            numberAt(dailyObject, "apparent_temperature_min", i),
                            numberAt(dailyObject, "apparent_temperature_max", i),
                            numberAt(dailyObject, "relative_humidity_2m_mean", i),
                            numberAt(dailyObject, "precipitation_sum", i),
                            numberAt(dailyObject, "precipitation_probability_max", i),
                            (int) numberAt(dailyObject, "weather_code", i)));
                }
            }
        }
        return new WeatherSnapshot(place, timezone, current.optString("time", ""),
                number(current, "temperature_2m"), number(current, "apparent_temperature"),
                number(current, "relative_humidity_2m"), number(current, "precipitation"),
                (int) number(current, "weather_code"), hours, days, json);
    }

    public JSONObject toJson() { return raw; }

    public static final class Hourly {
        public final String time;
        public final double temperature;
        public final double feelsLike;
        public final double humidity;
        public final double precipitation;
        public final double precipitationProbability;
        public final int weatherCode;
        Hourly(String time, double temperature, double feelsLike, double humidity,
               double precipitation, double precipitationProbability, int weatherCode) {
            this.time = time; this.temperature = temperature; this.feelsLike = feelsLike;
            this.humidity = humidity; this.precipitation = precipitation;
            this.precipitationProbability = precipitationProbability; this.weatherCode = weatherCode;
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
        Daily(String date, double minTemperature, double maxTemperature, double minFeelsLike,
              double maxFeelsLike, double meanHumidity, double precipitation,
              double precipitationProbability, int weatherCode) {
            this.date = date; this.minTemperature = minTemperature; this.maxTemperature = maxTemperature;
            this.minFeelsLike = minFeelsLike; this.maxFeelsLike = maxFeelsLike;
            this.meanHumidity = meanHumidity; this.precipitation = precipitation;
            this.precipitationProbability = precipitationProbability; this.weatherCode = weatherCode;
        }
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

    private static String stringAt(JSONArray array, int index) {
        return index < array.length() ? array.optString(index, "") : "";
    }
}
