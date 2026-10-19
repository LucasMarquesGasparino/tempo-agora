package com.tempoagora;

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

    public static void main(String[] args) {
        check(System.getProperty("java.version") != null, "a JVM informa a versão");

        if (failures > 0) {
            System.out.println(failures + " falha(s) em " + checks + " verificações");
            System.exit(1);
        }
        System.out.println("OK: " + checks + " verificações");
    }
}
