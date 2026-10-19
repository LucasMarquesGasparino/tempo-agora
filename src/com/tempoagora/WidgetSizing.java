package com.tempoagora;

/**
 * Decide quantas linhas de horas e dias cabem no widget. As alturas vêm medidas
 * do próprio layout no aparelho (Metrics); DEFAULT é só o plano B, em dp.
 */
public final class WidgetSizing {
    static final int COMPACT_HEIGHT = 110;
    static final int COMPACT_WIDTH = 160;
    /** Mínimo de horas quando as duas tabelas aparecem. */
    static final int MIN_HOURS = 2;

    /** Alturas em qualquer unidade (px medidos ou dp estimados), desde que todas iguais. */
    static final class Metrics {
        /** Widget sem nenhuma tabela: padding, cabeçalho, "agora", métricas e rodapé. */
        final int base;
        /** Título + cabeçalho de colunas da tabela de horas. */
        final int hoursSection;
        /** Título + cabeçalho de colunas da tabela de dias. */
        final int daysSection;
        /** Divisor entre as duas tabelas, quando as duas aparecem. */
        final int divider;
        /** Uma linha de previsão, com seu divisor. */
        final int row;
        /** Folga para não cortar o rodapé: o launcher pode usar outro tema/fonte. */
        final int slack;

        Metrics(int base, int hoursSection, int daysSection, int divider, int row, int slack) {
            this.base = base;
            this.hoursSection = hoursSection;
            this.daysSection = daysSection;
            this.divider = divider;
            this.row = Math.max(1, row);
            this.slack = slack;
        }
    }

    /** Estimativa em dp, usada só se a medição falhar. */
    static final Metrics DEFAULT = new Metrics(160, 36, 38, 9, 17, 2);

    private WidgetSizing() { }

    static boolean compact(int widthDp, int heightDp) {
        return heightDp < COMPACT_HEIGHT || widthDp < COMPACT_WIDTH;
    }

    /** Plano B em dp. Devolve {horas, dias}. */
    static int[] plan(int heightDp, int nHours, int nDays) {
        return plan(heightDp, DEFAULT, nHours, nDays);
    }

    /**
     * Devolve {horas, dias}. As horas de hoje ficam com metade das linhas (mínimo 2);
     * os dias ocupam o resto e, se faltarem dias, a sobra volta para as horas.
     * Sem espaço para as duas tabelas, mostra só as horas.
     */
    static int[] plan(int height, Metrics m, int nHours, int nDays) {
        nHours = Math.max(0, nHours);
        nDays = Math.max(0, nDays);
        int free = height - m.base - m.slack;

        if (nHours > 0 && nDays > 0) {
            int rows = (free - m.hoursSection - m.daysSection - m.divider) / m.row;
            if (rows >= MIN_HOURS + 1) {
                int hours = Math.min(nHours, Math.max(MIN_HOURS, rows / 2));
                int days = Math.min(nDays, rows - hours);
                hours = Math.min(nHours, rows - days);
                return new int[] {hours, days};
            }
        }
        if (nHours > 0) {
            int rows = (free - m.hoursSection) / m.row;
            if (rows >= 2) return new int[] {Math.min(nHours, rows), 0};
        }
        if (nDays > 0) {
            int rows = (free - m.daysSection) / m.row;
            if (rows >= 2) return new int[] {0, Math.min(nDays, rows)};
        }
        return new int[] {0, 0};
    }
}
