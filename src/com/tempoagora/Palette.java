package com.tempoagora;

import android.content.Context;
import android.content.res.Configuration;

/** Cores do app para o tema claro e o escuro, escolhidas pelo modo do sistema. */
final class Palette {
    final int background;
    final int surface;
    final int surfaceAlt;
    final int ink;
    final int muted;
    final int accent;
    final int rainText;
    final int chartRain;
    final int chance;
    final int temp;
    final int grid;
    final int paleAccent;
    final int selectedChip;
    final int tabTrack;
    final int border;
    final int expanded;
    final int divider;
    final int warningBg;
    final int warningText;
    final boolean dark;

    private Palette(boolean dark, int background, int surface, int surfaceAlt, int ink, int muted,
                    int accent, int rainText, int chartRain, int chance, int temp, int grid,
                    int paleAccent, int selectedChip, int tabTrack, int border, int expanded,
                    int divider, int warningBg, int warningText) {
        this.dark = dark;
        this.background = background;
        this.surface = surface;
        this.surfaceAlt = surfaceAlt;
        this.ink = ink;
        this.muted = muted;
        this.accent = accent;
        this.rainText = rainText;
        this.chartRain = chartRain;
        this.chance = chance;
        this.temp = temp;
        this.grid = grid;
        this.paleAccent = paleAccent;
        this.selectedChip = selectedChip;
        this.tabTrack = tabTrack;
        this.border = border;
        this.expanded = expanded;
        this.divider = divider;
        this.warningBg = warningBg;
        this.warningText = warningText;
    }

    private static final Palette LIGHT = new Palette(false,
            0xFFF4F8FA, 0xFFFFFFFF, 0xFFF6FAFC, 0xFF183544, 0xFF586C77,
            0xFF177FAE, 0xFF1C7FA8, 0xFF339DC5, 0xFF7556B5, 0xFFE07838, 0xFFE6EDF0,
            0xFFE8F5FB, 0xFFDCEFF7, 0xFFE2EBEF, 0xFFE1EAEE, 0xFFE8F4F9,
            0xFFE8EFF2, 0xFFFFF4DD, 0xFF7A5200);

    private static final Palette DARK = new Palette(true,
            0xFF0F1A20, 0xFF18262E, 0xFF1D2D36, 0xFFE5EEF2, 0xFF9BB0BB,
            0xFF5DB9E4, 0xFF6CC4EA, 0xFF3FA7D1, 0xFFA78BE6, 0xFFF08D4F, 0xFF2A3B44,
            0xFF173544, 0xFF1F4152, 0xFF1D2D36, 0xFF263842, 0xFF1C3846,
            0xFF263842, 0xFF3A2E12, 0xFFF2D08A);

    static Palette of(Context context) {
        int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES ? DARK : LIGHT;
    }
}
