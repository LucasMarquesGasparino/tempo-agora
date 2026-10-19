package com.tempoagora;

/** Decide quantas linhas de horas e dias cabem no widget, em dp. */
public final class WidgetSizing {
    /** Padding, cabeçalho, "agora", métricas e rodapé. */
    static final int BASE = 146;
    /** Título da seção e cabeçalho de colunas. */
    static final int SECTION = 30;
    static final int ROW = 16;
    static final int COMPACT_HEIGHT = 110;
    static final int COMPACT_WIDTH = 160;

    private WidgetSizing() { }

    static boolean compact(int widthDp, int heightDp) {
        return heightDp < COMPACT_HEIGHT || widthDp < COMPACT_WIDTH;
    }

    /** Devolve {horas, dias} a exibir. */
    static int[] plan(int heightDp, int nHours, int nDays) {
        int budget = heightDp - BASE;
        if (nHours > 0 && nDays > 0 && budget >= 2 * SECTION + 4 * ROW) {
            int rows = (budget - 2 * SECTION) / ROW;
            int hours = Math.min(nHours, Math.max(2, rows / 2));
            int days = Math.min(nDays, rows - hours);
            hours = Math.min(nHours, rows - days);
            return new int[] {hours, days};
        }
        int rows = (budget - SECTION) / ROW;
        if (rows >= 2) {
            return nHours > 0
                    ? new int[] {Math.min(nHours, rows), 0}
                    : new int[] {0, Math.min(nDays, rows)};
        }
        return new int[] {0, 0};
    }
}
