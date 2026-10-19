package com.tempoagora;

/** Ícone vetorial para cada condição, usado no app e no widget. */
final class WeatherIcons {
    private WeatherIcons() { }

    static int res(int code, boolean isDay) {
        switch (WeatherCodes.kind(code)) {
            case WeatherCodes.KIND_CLEAR:
                return isDay ? R.drawable.ic_wx_clear_day : R.drawable.ic_wx_clear_night;
            case WeatherCodes.KIND_PARTLY:
                return isDay ? R.drawable.ic_wx_partly_day : R.drawable.ic_wx_partly_night;
            case WeatherCodes.KIND_FOG:
                return R.drawable.ic_wx_fog;
            case WeatherCodes.KIND_DRIZZLE:
                return R.drawable.ic_wx_drizzle;
            case WeatherCodes.KIND_RAIN:
                return R.drawable.ic_wx_rain;
            case WeatherCodes.KIND_SHOWERS:
                return R.drawable.ic_wx_showers;
            case WeatherCodes.KIND_SNOW:
                return R.drawable.ic_wx_snow;
            case WeatherCodes.KIND_THUNDER:
                return R.drawable.ic_wx_thunder;
            default:
                return R.drawable.ic_wx_cloudy;
        }
    }
}
