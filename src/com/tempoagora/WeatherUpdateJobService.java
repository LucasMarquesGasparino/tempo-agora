package com.tempoagora;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Atualiza o lugar do widget quando o JobScheduler manda (periódico ou quando a rede voltar). */
public final class WeatherUpdateJobService extends JobService {
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();

    @Override public boolean onStartJob(JobParameters params) {
        Context app = getApplicationContext();
        WORK.execute(() -> {
            boolean reschedule = false;
            try {
                if (AppPrefs.widgetPlaceResolved(app) == null || !hasWidgets(app)) {
                    WidgetUpdater.cancelAll(app);
                    return;
                }
                boolean catchUp = params.getJobId() == WidgetUpdater.JOB_CATCH_UP;
                WeatherSnapshot cached = WeatherCache.read(app, AppPrefs.widgetPlaceResolved(app));
                boolean ok = true;
                if (catchUp || WidgetUpdater.needsRefresh(cached, System.currentTimeMillis())) {
                    ok = WeatherRepository.refreshWidgetPlace(app);
                }
                WidgetUpdater.renderAll(app);
                reschedule = catchUp && !ok && NetworkStatus.isOnline(app);
            } finally {
                jobFinished(params, reschedule);
            }
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        return true;
    }

    private static boolean hasWidgets(Context context) {
        return android.appwidget.AppWidgetManager.getInstance(context).getAppWidgetIds(
                new android.content.ComponentName(context, WeatherWidgetProvider.class)).length > 0;
    }
}
