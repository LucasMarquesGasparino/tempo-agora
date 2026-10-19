package com.tempoagora;

/** Unidades escolhidas pelo usuário. Os dados são sempre buscados em métrico; a conversão é só na exibição. */
public final class Units {
    public static final String TEMP_C = "c", TEMP_F = "f";
    public static final String WIND_KMH = "kmh", WIND_MS = "ms";
    public static final String PRECIP_MM = "mm", PRECIP_IN = "in";

    public final String temperature;
    public final String wind;
    public final String precipitation;

    /** Valores desconhecidos ou nulos viram o padrão métrico. */
    public Units(String temperature, String wind, String precipitation) {
        this.temperature = TEMP_F.equals(temperature) ? TEMP_F : TEMP_C;
        this.wind = WIND_MS.equals(wind) ? WIND_MS : WIND_KMH;
        this.precipitation = PRECIP_IN.equals(precipitation) ? PRECIP_IN : PRECIP_MM;
    }

    public static Units metric() {
        return new Units(TEMP_C, WIND_KMH, PRECIP_MM);
    }

    public double temperature(double celsius) {
        return TEMP_F.equals(temperature) ? celsius * 9 / 5 + 32 : celsius;
    }

    public double precipitation(double mm) {
        return PRECIP_IN.equals(precipitation) ? mm / 25.4 : mm;
    }

    public double wind(double kmh) {
        return WIND_MS.equals(wind) ? kmh / 3.6 : kmh;
    }

    public String temperatureSymbol() {
        return TEMP_F.equals(temperature) ? "°F" : "°C";
    }

    public String precipitationSymbol() {
        return PRECIP_IN.equals(precipitation) ? "pol" : "mm";
    }

    public String windSymbol() {
        return WIND_MS.equals(wind) ? "m/s" : "km/h";
    }

    public int precipitationDecimals() {
        return PRECIP_IN.equals(precipitation) ? 2 : 1;
    }

    public int windDecimals() {
        return WIND_MS.equals(wind) ? 1 : 0;
    }
}
