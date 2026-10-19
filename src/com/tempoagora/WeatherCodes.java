package com.tempoagora;

/** Códigos WMO do Open-Meteo: texto em pt-BR e categoria visual. */
public final class WeatherCodes {
    public static final int KIND_CLEAR = 0;
    public static final int KIND_PARTLY = 1;
    public static final int KIND_CLOUDY = 2;
    public static final int KIND_FOG = 3;
    public static final int KIND_DRIZZLE = 4;
    public static final int KIND_RAIN = 5;
    public static final int KIND_SHOWERS = 6;
    public static final int KIND_SNOW = 7;
    public static final int KIND_THUNDER = 8;
    public static final int KIND_UNKNOWN = 9;

    private WeatherCodes() { }

    public static String condition(int code) {
        if (code < 0) return "Sem dados";
        switch (code) {
            case 0: return "Céu limpo";
            case 1: return "Predominantemente limpo";
            case 2: return "Parcialmente nublado";
            case 3: return "Nublado";
            case 45: return "Neblina";
            case 48: return "Neblina com geada";
            case 51: return "Garoa fraca";
            case 53: return "Garoa";
            case 55: return "Garoa forte";
            case 56:
            case 57: return "Garoa congelante";
            case 61: return "Chuva fraca";
            case 63: return "Chuva moderada";
            case 65: return "Chuva forte";
            case 66:
            case 67: return "Chuva congelante";
            case 71: return "Neve fraca";
            case 73: return "Neve";
            case 75: return "Neve forte";
            case 77: return "Grãos de neve";
            case 80: return "Pancadas fracas";
            case 81: return "Pancadas de chuva";
            case 82: return "Pancadas fortes";
            case 85:
            case 86: return "Pancadas de neve";
            case 95: return "Trovoadas";
            case 96:
            case 99: return "Trovoadas com granizo";
            default: return "Condição variável";
        }
    }

    public static int kind(int code) {
        if (code < 0) return KIND_UNKNOWN;
        if (code == 0) return KIND_CLEAR;
        if (code == 1 || code == 2) return KIND_PARTLY;
        if (code == 3) return KIND_CLOUDY;
        if (code == 45 || code == 48) return KIND_FOG;
        if (code >= 51 && code <= 57) return KIND_DRIZZLE;
        if (code >= 61 && code <= 67) return KIND_RAIN;
        if (code >= 80 && code <= 82) return KIND_SHOWERS;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return KIND_SNOW;
        if (code >= 95 && code <= 99) return KIND_THUNDER;
        return KIND_CLOUDY;
    }
}
