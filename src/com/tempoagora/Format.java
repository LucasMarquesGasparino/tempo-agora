package com.tempoagora;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Formatação de valores e datas em pt-BR, determinística e sem depender do ICU para nomes. */
public final class Format {
    static final Locale PT_BR = new Locale("pt", "BR");

    static final String[] WEEKDAYS_SHORT = {"Dom", "Seg", "Ter", "Qua", "Qui", "Sex", "Sáb"};
    static final String[] WEEKDAYS_LONG = {
            "domingo", "segunda-feira", "terça-feira", "quarta-feira",
            "quinta-feira", "sexta-feira", "sábado"};
    static final String[] MONTHS_SHORT = {
            "jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez"};
    static final String[] MONTHS_LONG = {
            "janeiro", "fevereiro", "março", "abril", "maio", "junho",
            "julho", "agosto", "setembro", "outubro", "novembro", "dezembro"};
    static final String[] COMPASS = {"N", "NE", "L", "SE", "S", "SO", "O", "NO"};

    private Format() { }

    /** Número com casas fixas no máximo; NaN/infinito viram "—"; zero nunca aparece como "-0". */
    static String number(double value, int decimals, String suffix) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return "—";
        BigDecimal rounded = BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP);
        if (rounded.signum() == 0) rounded = BigDecimal.ZERO;
        NumberFormat format = NumberFormat.getNumberInstance(PT_BR);
        format.setMinimumFractionDigits(0);
        format.setMaximumFractionDigits(decimals);
        return format.format(rounded) + suffix;
    }

    static String temperature(double celsius, Units units) {
        return number(units.temperature(celsius), 0, "°");
    }

    static String precipitation(double mm, Units units) {
        return number(units.precipitation(mm), units.precipitationDecimals(), " " + units.precipitationSymbol());
    }

    static String wind(double kmh, Units units) {
        return number(units.wind(kmh), units.windDecimals(), " " + units.windSymbol());
    }

    static String percent(double value) {
        return number(value, 0, "%");
    }

    static String compass(double degrees) {
        if (Double.isNaN(degrees) || Double.isInfinite(degrees)) return "";
        double normalized = ((degrees % 360) + 360) % 360;
        int index = (int) Math.round(normalized / 45) % 8;
        return COMPASS[index];
    }

    /** Índice do dia da semana (0 = domingo) ou -1 se a data é inválida. */
    static int weekdayIndex(String isoDate) {
        Calendar calendar = ForecastTime.utcCalendar(isoDate);
        return calendar == null ? -1 : calendar.get(Calendar.DAY_OF_WEEK) - 1;
    }

    static String weekdayShort(String isoDate) {
        int index = weekdayIndex(isoDate);
        return index < 0 ? orEmpty(isoDate) : WEEKDAYS_SHORT[index];
    }

    static String weekdayLong(String isoDate) {
        int index = weekdayIndex(isoDate);
        return index < 0 ? orEmpty(isoDate) : WEEKDAYS_LONG[index];
    }

    /** "18 out" */
    static String dayMonth(String isoDate) {
        Calendar calendar = ForecastTime.utcCalendar(isoDate);
        if (calendar == null) return orEmpty(isoDate);
        return calendar.get(Calendar.DAY_OF_MONTH) + " " + MONTHS_SHORT[calendar.get(Calendar.MONTH)];
    }

    /** "Dom, 18 out" */
    static String weekdayDayMonth(String isoDate) {
        if (weekdayIndex(isoDate) < 0) return orEmpty(isoDate);
        return weekdayShort(isoDate) + ", " + dayMonth(isoDate);
    }

    /** "domingo, 18 de outubro" */
    static String longDate(String isoDate) {
        Calendar calendar = ForecastTime.utcCalendar(isoDate);
        if (calendar == null) return orEmpty(isoDate);
        return weekdayLong(isoDate) + ", " + calendar.get(Calendar.DAY_OF_MONTH)
                + " de " + MONTHS_LONG[calendar.get(Calendar.MONTH)];
    }

    /** "Hoje", "Amanhã" ou "Ter 20". */
    static String dayLabel(String isoDate, String todayIso) {
        Calendar calendar = ForecastTime.utcCalendar(isoDate);
        if (calendar == null) return orEmpty(isoDate);
        if (isoDate.equals(todayIso)) return "Hoje";
        if (isoDate.equals(ForecastTime.addDays(todayIso, 1))) return "Amanhã";
        return WEEKDAYS_SHORT[calendar.get(Calendar.DAY_OF_WEEK) - 1] + " " + calendar.get(Calendar.DAY_OF_MONTH);
    }

    /** "15h" */
    static String hourLabel(String isoTime) {
        if (isoTime == null || isoTime.length() < 13) return "—";
        try {
            return Integer.parseInt(isoTime.substring(11, 13)) + "h";
        } catch (NumberFormatException invalid) {
            return "—";
        }
    }

    /** "15:00" */
    static String clock(String isoTime) {
        if (isoTime == null || isoTime.length() < 16) return "—";
        return isoTime.substring(11, 16);
    }

    /** Relógio do aparelho em millis: "HH:mm". */
    static String clockOf(long millis) {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(millis));
    }

    static String relativeAge(long ageMillis) {
        if (ageMillis < 60_000L) return "agora mesmo";
        if (ageMillis < 3_600_000L) return "há " + (ageMillis / 60_000L) + " min";
        if (ageMillis < 86_400_000L) return "há " + (ageMillis / 3_600_000L) + " h";
        long days = ageMillis / 86_400_000L;
        return days == 1 ? "há 1 dia" : "há " + days + " dias";
    }

    static String uvLabel(double uv) {
        if (Double.isNaN(uv)) return "";
        if (uv < 3) return "baixo";
        if (uv < 6) return "moderado";
        if (uv < 8) return "alto";
        if (uv < 11) return "muito alto";
        return "extremo";
    }

    static String capitalize(String text) {
        if (text == null || text.isEmpty()) return "";
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
