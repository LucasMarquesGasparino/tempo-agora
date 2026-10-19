package com.tempoagora;

import android.content.Context;
import java.util.Collections;
import java.util.List;

/** Busca e gravação de previsões. Chame sempre fora da UI thread. */
final class WeatherRepository {
    private WeatherRepository() { }

    static List<WeatherSnapshot> refresh(Context context, List<Place> places) throws WeatherApiClient.ApiException {
        List<WeatherSnapshot> snapshots = new WeatherApiClient().fetch(places);
        for (WeatherSnapshot snapshot : snapshots) WeatherCache.write(context, snapshot);
        return snapshots;
    }

    /** Atualiza só o lugar do widget. false se não houver lugar, sem rede ou se a busca falhar. */
    static boolean refreshWidgetPlace(Context context) {
        Place place = AppPrefs.widgetPlaceResolved(context);
        if (place == null || !NetworkStatus.isOnline(context)) return false;
        try {
            refresh(context, Collections.singletonList(place));
            return true;
        } catch (WeatherApiClient.ApiException failed) {
            return false;
        }
    }
}
