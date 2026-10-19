package com.tempoagora;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Widget da tela inicial. Fino: todo trabalho acontece em WidgetUpdater, em thread de fundo
 * (goAsync), nunca na thread principal.
 */
public final class WeatherWidgetProvider extends AppWidgetProvider {
    static final String ACTION_REFRESH = "com.tempoagora.action.REFRESH_WIDGET";
    static final String ACTION_TICK = "com.tempoagora.action.WIDGET_TICK";
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();

    private interface Task {
        void run(Context app);
    }

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_REFRESH.equals(action)) {
            work(context, WeatherWidgetProvider::refreshNow);
            return;
        }
        if (ACTION_TICK.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
            work(context, WidgetUpdater::renderAll);
            return;
        }
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            work(context, app -> {
                WidgetUpdater.renderAll(app);
                WidgetUpdater.schedulePeriodic(app);
            });
            return;
        }
        super.onReceive(context, intent);
    }

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        work(context, app -> {
            WidgetUpdater.renderAll(app);
            WidgetUpdater.schedulePeriodic(app);
            WidgetUpdater.requestIfStale(app);
        });
    }

    @Override public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager,
                                                    int appWidgetId, Bundle newOptions) {
        work(context, WidgetUpdater::renderAll);
    }

    @Override public void onEnabled(Context context) {
        work(context, WidgetUpdater::schedulePeriodic);
    }

    @Override public void onDisabled(Context context) {
        work(context, WidgetUpdater::cancelAll);
    }

    /** Chamado pelo app quando a previsão ou o lugar do widget mudam. */
    public static void refreshViews(Context context) {
        WidgetUpdater.onPlaceOrDataChanged(context);
    }

    private static void refreshNow(Context app) {
        if (AppPrefs.widgetPlaceResolved(app) == null) {
            WidgetUpdater.renderAll(app);
            return;
        }
        WidgetUpdater.renderAll(app, "Buscando previsão…");
        if (!NetworkStatus.isOnline(app)) {
            WidgetUpdater.renderAll(app, WidgetUpdater.staleNotice(app, "Sem conexão"));
            WidgetUpdater.requestRefreshWhenOnline(app);
            return;
        }
        if (WeatherRepository.refreshWidgetPlace(app)) {
            WidgetUpdater.renderAll(app);
        } else {
            WidgetUpdater.renderAll(app, WidgetUpdater.staleNotice(app, "Falha ao atualizar"));
            WidgetUpdater.requestRefreshWhenOnline(app);
        }
    }

    private void work(Context context, Task task) {
        PendingResult pending = goAsync();
        Context app = context.getApplicationContext();
        WORK.execute(() -> {
            try {
                task.run(app);
            } finally {
                pending.finish();
            }
        });
    }
}
