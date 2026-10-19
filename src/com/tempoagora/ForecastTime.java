package com.tempoagora;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.SimpleTimeZone;
import java.util.TimeZone;

/** Operações sobre horários ISO locais ("yyyy-MM-ddTHH:mm") da previsão. Sem java.time (minSdk 24). */
public final class ForecastTime {
    private ForecastTime() { }

    /** Hora atual no fuso do lugar. Sem fuso válido, usa o deslocamento que a API informou. */
    static String localNow(String timezoneId, int utcOffsetSeconds, long nowMillis) {
        TimeZone zone = null;
        if (timezoneId != null && !"auto".equals(timezoneId)) {
            TimeZone candidate = TimeZone.getTimeZone(timezoneId);
            if (timezoneId.equals(candidate.getID())) zone = candidate;
        }
        if (zone == null) zone = new SimpleTimeZone(utcOffsetSeconds * 1000, "UTC");
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        format.setTimeZone(zone);
        return format.format(new Date(nowMillis));
    }

    /** Soma dias a uma data ISO "yyyy-MM-dd". Data inválida devolve a própria entrada. */
    static String addDays(String isoDate, int days) {
        Calendar calendar = utcCalendar(isoDate);
        if (calendar == null) return isoDate;
        calendar.add(Calendar.DAY_OF_MONTH, days);
        return String.format(Locale.US, "%04d-%02d-%02d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH));
    }

    /** Calendário em UTC para uma data ISO, ou null se for inválida. */
    static Calendar utcCalendar(String isoDate) {
        if (isoDate == null || isoDate.length() < 10) return null;
        if (isoDate.charAt(4) != '-' || isoDate.charAt(7) != '-') return null;
        try {
            Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
            calendar.clear();
            calendar.setLenient(false);
            calendar.set(Integer.parseInt(isoDate.substring(0, 4)),
                    Integer.parseInt(isoDate.substring(5, 7)) - 1,
                    Integer.parseInt(isoDate.substring(8, 10)));
            calendar.getTimeInMillis();
            return calendar;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    /** Índice do horário da hora atual (mesmo "yyyy-MM-ddTHH"), ou -1. */
    static int indexOfHour(List<String> times, String now) {
        String key = hourKey(now);
        if (times == null || key == null) return -1;
        for (int i = 0; i < times.size(); i++) {
            if (key.equals(hourKey(times.get(i)))) return i;
        }
        return -1;
    }

    /** Horários de hoje a partir da hora atual. */
    static List<Integer> remainingToday(List<String> times, String now, boolean includeCurrentHour) {
        List<Integer> out = new ArrayList<>();
        String nowKey = hourKey(now);
        if (times == null || nowKey == null) return out;
        String today = now.substring(0, 10);
        for (int i = 0; i < times.size(); i++) {
            String time = times.get(i);
            String key = hourKey(time);
            if (key == null || !today.equals(time.substring(0, 10))) continue;
            int order = key.compareTo(nowKey);
            if (order > 0 || (includeCurrentHour && order == 0)) out.add(i);
        }
        return out;
    }

    /** Todos os horários de uma data. Se a data é hoje, descarta as horas passadas (mantém a atual). */
    static List<Integer> forDate(List<String> times, String date, String now) {
        List<Integer> out = new ArrayList<>();
        String nowKey = hourKey(now);
        if (times == null || date == null) return out;
        boolean isToday = now != null && now.length() >= 10 && date.equals(now.substring(0, 10));
        for (int i = 0; i < times.size(); i++) {
            String time = times.get(i);
            String key = hourKey(time);
            if (key == null || !date.equals(time.substring(0, 10))) continue;
            if (isToday && nowKey != null && key.compareTo(nowKey) < 0) continue;
            out.add(i);
        }
        return out;
    }

    /** Escolhe {@code count} índices espalhados, mantendo o primeiro e o último. */
    static List<Integer> sampleEvenly(List<Integer> indices, int count) {
        List<Integer> out = new ArrayList<>();
        if (indices == null || count <= 0 || indices.isEmpty()) return out;
        int n = indices.size();
        if (count >= n) {
            out.addAll(indices);
            return out;
        }
        if (count == 1) {
            out.add(indices.get(0));
            return out;
        }
        for (int i = 0; i < count; i++) {
            long position = Math.round((double) i * (n - 1) / (count - 1));
            out.add(indices.get((int) position));
        }
        return out;
    }

    private static String hourKey(String time) {
        if (time == null || time.length() < 13) return null;
        return time.substring(0, 13);
    }
}
