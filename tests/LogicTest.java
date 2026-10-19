package com.tempoagora;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/** Testes JVM puros da lógica do app. Rode com: sh tests/run.sh */
public final class LogicTest {
    private static int checks;
    private static int failures;

    static void check(boolean ok, String message) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FALHOU: " + message);
        }
    }

    static void equal(Object expected, Object actual, String message) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        check(ok, message + " (esperado: " + expected + ", obtido: " + actual + ")");
    }

    static void equal(int[] expected, int[] actual, String message) {
        check(Arrays.equals(expected, actual),
                message + " (esperado: " + Arrays.toString(expected) + ", obtido: " + Arrays.toString(actual) + ")");
    }

    static void near(double expected, double actual, String message) {
        check(Math.abs(expected - actual) < 1e-9, message + " (esperado: " + expected + ", obtido: " + actual + ")");
    }

    /** 48 horários: 2026-10-18T00:00 até 2026-10-19T23:00. */
    static List<String> hours48() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < 48; i++) {
            String day = i < 24 ? "2026-10-18" : "2026-10-19";
            out.add(String.format("%sT%02d:00", day, i % 24));
        }
        return out;
    }

    static void testFormat() {
        equal("1,5 mm", Format.number(1.5, 1, " mm"), "número com vírgula decimal");
        equal("0°", Format.number(-0.3, 0, "°"), "zero arredondado nunca sai como -0");
        equal("—", Format.number(Double.NaN, 0, "°"), "NaN vira travessão");
        equal("—", Format.number(Double.POSITIVE_INFINITY, 0, "°"), "infinito vira travessão");
        equal("Dom", Format.weekdayShort("2026-10-18"), "18/10/2026 é domingo");
        equal("Seg", Format.weekdayShort("2026-10-19"), "19/10/2026 é segunda");
        equal("18 out", Format.dayMonth("2026-10-18"), "dia e mês abreviados");
        equal("Dom, 18 out", Format.weekdayDayMonth("2026-10-18"), "dia da semana com data");
        equal("domingo, 18 de outubro", Format.longDate("2026-10-18"), "data por extenso");
        equal("Hoje", Format.dayLabel("2026-10-18", "2026-10-18"), "rótulo de hoje");
        equal("Amanhã", Format.dayLabel("2026-10-19", "2026-10-18"), "rótulo de amanhã");
        equal("Ter 20", Format.dayLabel("2026-10-20", "2026-10-18"), "rótulo de outro dia");
        equal("", Format.weekdayShort(null), "dia da semana de nulo");
        equal("data-ruim", Format.weekdayShort("data-ruim"), "data inválida devolve a própria string");
        equal("15h", Format.hourLabel("2026-10-18T15:00"), "rótulo de hora");
        equal("15:00", Format.clock("2026-10-18T15:00"), "relógio de horário ISO");
        equal("—", Format.clock("15"), "relógio de texto curto");
        equal("agora mesmo", Format.relativeAge(30_000), "idade menor que 1 min");
        equal("agora mesmo", Format.relativeAge(-5_000), "idade negativa");
        equal("há 5 min", Format.relativeAge(5 * 60_000L), "idade em minutos");
        equal("há 3 h", Format.relativeAge(3 * 3_600_000L), "idade em horas");
        equal("há 2 dias", Format.relativeAge(50 * 3_600_000L), "idade em dias");
        equal("há 1 dia", Format.relativeAge(25 * 3_600_000L), "um dia no singular");
        equal("N", Format.compass(0), "norte");
        equal("L", Format.compass(90), "leste");
        equal("SO", Format.compass(225), "sudoeste");
        equal("N", Format.compass(359), "360 graus volta ao norte");
        equal("", Format.compass(Double.NaN), "direção ausente");
        equal("baixo", Format.uvLabel(2.9), "UV baixo");
        equal("extremo", Format.uvLabel(11), "UV extremo");
        equal("", Format.uvLabel(Double.NaN), "UV ausente");
        equal("Chuva", Format.capitalize("chuva"), "primeira letra maiúscula");
    }

    static void testUnits() {
        Units fahrenheit = new Units("f", "ms", "in");
        near(32.0, fahrenheit.temperature(0), "0 °C são 32 °F");
        near(10.0, fahrenheit.wind(36), "36 km/h são 10 m/s");
        equal(Units.TEMP_C, Units.metric().temperature, "padrão é métrico");
        Units invalid = new Units("x", "y", "z");
        equal(Units.TEMP_C, invalid.temperature, "temperatura inválida cai no métrico");
        equal(Units.WIND_KMH, invalid.wind, "vento inválido cai no métrico");
        equal(Units.PRECIP_MM, invalid.precipitation, "chuva inválida cai no métrico");
        equal("1,5 mm", Format.precipitation(1.5, Units.metric()), "chuva em mm");
        equal("1 pol", Format.precipitation(25.4, new Units("c", "kmh", "in")), "25,4 mm são 1 polegada");
        equal("23°", Format.temperature(23.2, Units.metric()), "temperatura em Celsius");
        equal("73°", Format.temperature(23, new Units("f", "kmh", "mm")), "23 °C são 73 °F");
        equal("10 m/s", Format.wind(36, new Units("c", "ms", "mm")), "vento em m/s");
    }

    static void testForecastTime() {
        equal("2027-01-01", ForecastTime.addDays("2026-12-31", 1), "vira o ano");
        equal("2028-02-29", ForecastTime.addDays("2028-02-28", 1), "ano bissexto");
        equal("2026-10-18", ForecastTime.addDays("2026-10-19", -1), "soma negativa");
        equal("nope", ForecastTime.addDays("nope", 1), "data inválida fica igual");

        // 1760800000000 ms = 2025-10-18T15:06:40Z; São Paulo está em UTC-3 (sem horário de verão desde 2019).
        String saoPaulo = ForecastTime.localNow("America/Sao_Paulo", -10800, 1760800000000L);
        equal("2025-10-18T12:06", saoPaulo, "15:06 UTC são 12:06 em São Paulo");
        equal(saoPaulo, ForecastTime.localNow("auto", -10800, 1760800000000L),
                "fuso auto usa o deslocamento da API");
        equal("1970-01-01T01:00", ForecastTime.localNow("Fuso/Invalido", 3600, 0L),
                "fuso inválido usa o deslocamento");

        List<String> times = hours48();
        String now = "2026-10-18T21:40";
        equal(21, ForecastTime.indexOfHour(times, now), "índice da hora atual");
        equal(-1, ForecastTime.indexOfHour(times, "2030-01-01T10:00"), "hora fora da lista");
        equal(Arrays.asList(21, 22, 23), ForecastTime.remainingToday(times, now, true),
                "horas restantes incluindo a atual");
        equal(Arrays.asList(22, 23), ForecastTime.remainingToday(times, now, false),
                "horas restantes sem a atual");
        equal(24, ForecastTime.forDate(times, "2026-10-19", now).size(), "amanhã tem as 24 horas");
        equal(Arrays.asList(21, 22, 23), ForecastTime.forDate(times, "2026-10-18", now),
                "hoje descarta horas passadas");
        check(ForecastTime.remainingToday(null, now, true).isEmpty(), "lista nula não quebra");
        check(ForecastTime.remainingToday(times, "ruim", true).isEmpty(), "agora inválido não quebra");

        List<Integer> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) ten.add(i);
        List<Integer> sampled = ForecastTime.sampleEvenly(ten, 8);
        equal(8, sampled.size(), "amostra de 8 horários");
        equal(0, sampled.get(0), "amostra mantém o primeiro");
        equal(9, sampled.get(sampled.size() - 1), "amostra mantém o último");
        equal(8, new HashSet<>(sampled).size(), "amostra sem repetidos");
        equal(0, ForecastTime.sampleEvenly(ten, 0).size(), "amostra de zero itens");
        equal(10, ForecastTime.sampleEvenly(ten, 20).size(), "pedir mais que o total devolve tudo");
    }

    static void testWeatherCodes() {
        equal("Sem dados", WeatherCodes.condition(-1), "código negativo");
        equal("Céu limpo", WeatherCodes.condition(0), "céu limpo");
        equal("Condição variável", WeatherCodes.condition(4), "código desconhecido");
        equal(WeatherCodes.KIND_PARTLY, WeatherCodes.kind(2), "parcialmente nublado");
        equal(WeatherCodes.KIND_THUNDER, WeatherCodes.kind(96), "trovoada com granizo");
        equal(WeatherCodes.KIND_SNOW, WeatherCodes.kind(77), "grãos de neve");
        equal(WeatherCodes.KIND_CLOUDY, WeatherCodes.kind(4), "código sem categoria vira nublado");
        equal(WeatherCodes.KIND_UNKNOWN, WeatherCodes.kind(-1), "sem dados");
    }

    static void testWidgetSizing() {
        equal(new int[] {2, 8}, WidgetSizing.plan(430, 8, 14), "widget grande: dias têm prioridade");
        equal(new int[] {2, 13}, WidgetSizing.plan(500, 2, 14), "poucas horas: sobra vai para os dias");
        equal(new int[] {8, 3}, WidgetSizing.plan(600, 8, 3), "poucos dias: sobra volta para as horas");
        equal(new int[] {0, 2}, WidgetSizing.plan(250, 8, 7), "widget médio mostra dias");
        equal(new int[] {0, 5}, WidgetSizing.plan(300, 2, 7), "sem espaço para as duas tabelas, só dias");
        equal(new int[] {6, 0}, WidgetSizing.plan(300, 8, 0), "sem dias, mostra horas");
        equal(new int[] {0, 0}, WidgetSizing.plan(150, 8, 7), "widget pequeno não mostra tabelas");
        equal(new int[] {0, 0}, WidgetSizing.plan(430, 0, 0), "sem dados, sem tabelas");
        WidgetSizing.Metrics px = new WidgetSizing.Metrics(440, 99, 104, 25, 47, 17);
        equal(new int[] {2, 4}, WidgetSizing.plan(1000, px, 8, 14), "alturas medidas em px");
        equal(new int[] {4, 13}, WidgetSizing.plan(1500, px, 8, 14), "horas limitadas a 4 enquanto faltam dias");
        equal(new int[] {7, 14}, WidgetSizing.plan(1700, px, 8, 14), "todos os 14 dias quando cabem");
        check(WidgetSizing.compact(250, 100), "altura baixa é compacto");
        check(!WidgetSizing.compact(250, 430), "tamanho grande não é compacto");
    }

    public static void main(String[] args) {
        testFormat();
        testUnits();
        testForecastTime();
        testWeatherCodes();
        testWidgetSizing();

        if (failures > 0) {
            System.out.println(failures + " falha(s) em " + checks + " verificações");
            System.exit(1);
        }
        System.out.println("OK: " + checks + " verificações");
    }
}
