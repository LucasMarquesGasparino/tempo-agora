package com.tempoagora;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQUEST_LOCATION = 410;
    private static final int INK = Color.rgb(24, 53, 68);
    private static final int MUTED = Color.rgb(108, 128, 139);
    private static final int BLUE = Color.rgb(23, 127, 174);
    private static final int RAIN = Color.rgb(51, 157, 197);
    private static final int PALE_BLUE = Color.rgb(232, 245, 251);
    private static final int BACKGROUND = Color.rgb(244, 248, 250);
    private static final int BORDER = Color.rgb(225, 234, 238);

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final WeatherApiClient api = new WeatherApiClient();
    private final List<Place> favorites = new ArrayList<>();
    private final List<View> dailyForecastRows = new ArrayList<>();
    private final java.util.LinkedHashMap<String, WeatherSnapshot> snapshots = new java.util.LinkedHashMap<>();

    private SharedPreferences preferences;
    private Place devicePlace;
    private Place activePlace;
    private RefreshScrollView scrollView;
    private LinearLayout page;
    private boolean dailyTab;
    private boolean refreshing;
    private boolean refreshRequestedAgain;
    private boolean manualSelectionInSession;
    private boolean locationFixReceived;
    private boolean permissionRequestPending;
    private boolean locationSettingsPending;
    private String statusMessage = "";
    private int selectedHour = -1;
    private int selectedDay = -1;
    private int pendingDailyScroll = -1;
    private LocationManager locationManager;
    private LocationListener legacyLocationListener;
    private CancellationSignal locationCancellation;
    private Runnable locationTimeout;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        preferences = getSharedPreferences("tempo_agora", MODE_PRIVATE);
        loadFavorites();
        devicePlace = readPlace("last_current");
        if (devicePlace != null) devicePlace = devicePlace.asCurrentLocation();
        activePlace = devicePlace != null ? devicePlace : readPlace("last_manual");
        if (activePlace == null && !favorites.isEmpty()) activePlace = favorites.get(0);
        persistWidgetPlace();
        loadCachedSnapshots();
        buildShell();
        renderPage();
        beginLocationStartup();
    }

    @Override protected void onDestroy() {
        stopLocationUpdates();
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        if (locationSettingsPending) {
            locationSettingsPending = false;
            if (hasLocationPermission()) {
                statusMessage = "Localização permitida · atualizando sua cidade…";
                renderPage();
                requestCurrentLocation();
            }
        }
    }

    private void buildShell() {
        scrollView = new RefreshScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(BACKGROUND);
        scrollView.setOnPullRefreshListener(() -> refreshWeather(true));
        if (Build.VERSION.SDK_INT >= 20) {
            scrollView.setOnApplyWindowInsetsListener((view, insets) -> {
                view.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
                return insets;
            });
        }
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(6), dp(16), dp(24));
        scrollView.addView(page, new ScrollView.LayoutParams(-1, -2));
        setContentView(scrollView);
        if (Build.VERSION.SDK_INT >= 20) scrollView.post(() -> scrollView.requestApplyInsets());
    }

    private void renderPage() {
        if (page == null) return;
        int oldScroll = scrollView == null ? 0 : scrollView.getScrollY();
        page.removeAllViews();
        dailyForecastRows.clear();
        page.addView(buildToolbar(), matchWrap());
        page.addView(buildPlaceHeader(), margins(-1, -2, 0, 0, 0, 7));

        if (!statusMessage.isEmpty()) page.addView(buildStatusCard(), margins(-1, -2, 0, 0, 0, 8));
        WeatherSnapshot snapshot = activePlace == null ? null : snapshots.get(activePlace.key());
        if (snapshot == null) page.addView(buildLoadingCard(), margins(-1, -2, 0, 0, 0, 9));
        else page.addView(buildCurrentCard(snapshot), margins(-1, -2, 0, 0, 0, 9));

        if (!favorites.isEmpty() || devicePlace != null) {
            page.addView(sectionHeading("Seus lugares", "Toque para consultar"), margins(-1, -2, 0, 0, 0, 4));
            page.addView(buildFavoriteStrip(), margins(-1, dp(56), 0, 0, 0, 8));
        }

        page.addView(buildTabs(), margins(-1, dp(44), 0, 0, 0, 8));
        if (snapshot != null) {
            if (dailyTab) buildDailyForecast(snapshot);
            else buildHourlyForecast(snapshot);
        } else {
            page.addView(infoCard("A previsão aparece assim que uma cidade for carregada."), matchWrap());
        }
        page.addView(buildAttribution(), margins(-1, -2, 0, 12, 0, 4));
        if (scrollView != null) scrollView.post(() -> {
            scrollView.scrollTo(0, Math.max(0, oldScroll));
            int index = pendingDailyScroll;
            pendingDailyScroll = -1;
            if (dailyTab && index >= 0 && index < dailyForecastRows.size()) {
                int top = topInScrollContent(dailyForecastRows.get(index));
                scrollView.smoothScrollTo(0, Math.max(0, top - dp(72)));
            }
        });
    }

    private View buildToolbar() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, dp(4));
        TextView mark = label("Tempo Agora", 19, INK, true);
        row.addView(mark, new LinearLayout.LayoutParams(0, -2, 1));
        TextView search = actionButton("Buscar", "Buscar cidade", this::openCitySearch);
        search.setTextSize(13);
        search.setPadding(dp(12), 0, dp(12), 0);
        row.addView(search, margins(-2, dp(42), 0, 0, 6, 0));
        row.addView(actionButton("▦", "Adicionar widget à tela inicial", this::pinWeatherWidget),
                margins(dp(42), dp(42), 0, 0, 6, 0));
        row.addView(actionButton(refreshing ? "…" : "↻", "Atualizar clima", () -> refreshWeather(true)), size(dp(42), dp(42)));
        return row;
    }

    private View buildPlaceHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        String name = activePlace == null ? "Escolha uma cidade" : activePlace.name;
        texts.addView(label(name, 21, INK, true), wrapWrap());
        String subtitle = activePlace == null ? "Sua previsão começa aqui" : activePlace.subtitle();
        texts.addView(label(subtitle, 12, MUTED, false), margins(-1, -2, 0, 1, 0, 0));
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        String star = activePlace != null && isFavorite(activePlace) ? "★" : "☆";
        row.addView(actionButton(star, "Favoritar cidade", () -> toggleFavorite(activePlace)), size(dp(44), dp(44)));
        return row;
    }

    private View buildStatusCard() {
        LinearLayout card = verticalCard(PALE_BLUE);
        card.addView(label(statusMessage, 13, INK, false), matchWrap());
        if (!hasLocationPermission()) {
            Button location = button("Permitir localização", true);
            location.setOnClickListener(v -> {
                if (preferences.getBoolean("location_asked", false)
                        && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)
                        && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    openAppLocationSettings();
                } else requestLocationPermission();
            });
            card.addView(location, margins(-1, dp(44), 0, 10, 0, 0));
        }
        if (activePlace == null) {
            Button search = button("Buscar uma cidade", true);
            search.setOnClickListener(v -> openCitySearch());
            card.addView(search, margins(-1, dp(44), 0, 10, 0, 0));
        }
        return card;
    }

    private View buildLoadingCard() {
        LinearLayout card = verticalCard(Color.WHITE);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ProgressBar progress = new ProgressBar(this);
        row.addView(progress, size(dp(30), dp(30)));
        TextView text = label(refreshing ? "Atualizando clima…" : "Buscando sua localização e previsão…", 15, INK, true);
        row.addView(text, margins(-1, -2, 12, 0, 0, 0));
        card.addView(row, matchWrap());
        TextView note = label("Você também pode escolher uma cidade pelo botão de busca.", 12, MUTED, false);
        card.addView(note, margins(-1, -2, 0, 10, 0, 0));
        return card;
    }

    private View buildCurrentCard(WeatherSnapshot snapshot) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(17), dp(15), dp(17), dp(13));
        card.setBackground(weatherBackdrop(snapshot.currentWeatherCode));
        LinearLayout hero = new LinearLayout(this);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout primary = new LinearLayout(this);
        primary.setOrientation(LinearLayout.VERTICAL);
        primary.addView(label("AGORA", 10, Color.argb(210, 255, 255, 255), true), wrapWrap());
        String temperature = value(snapshot.currentTemperature, 0, "°");
        primary.addView(label(temperature, 56, Color.WHITE, true), margins(-1, -2, 0, -1, 0, 0));
        primary.addView(label(condition(snapshot.currentWeatherCode), 14, Color.WHITE, true), wrapWrap());
        WeatherSnapshot.Daily today = snapshot.daily.isEmpty() ? null : snapshot.daily.get(0);
        if (today != null) {
            primary.addView(label("Máx " + value(today.maxTemperature, 0, "°")
                    + "  ·  Mín " + value(today.minTemperature, 0, "°"), 12,
                    Color.argb(220, 255, 255, 255), false), margins(-1, -2, 0, 3, 0, 0));
        }
        hero.addView(primary, new LinearLayout.LayoutParams(0, -2, 1));
        TextView icon = label(weatherIcon(snapshot.currentWeatherCode), 48, Color.WHITE, false);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(rounded(Color.argb(34, 255, 255, 255), 22));
        hero.addView(icon, size(dp(72), dp(72)));
        card.addView(hero, matchWrap());

        View divider = new View(this);
        divider.setBackgroundColor(Color.argb(70, 255, 255, 255));
        card.addView(divider, margins(-1, dp(1), 0, 11, 0, 9));
        card.addView(metricRow(
                heroMetric("Sensação", value(snapshot.currentFeelsLike, 0, "°")),
                heroMetric("Umidade", value(snapshot.currentHumidity, 0, "%")),
                heroMetric("Chuva", value(snapshot.currentPrecipitation, 1, " mm"))), matchWrap());
        WeatherSnapshot.Hourly next = nextHour(snapshot);
        String nextLine = next == null ? "Próxima hora · sem previsão disponível"
                : "Próxima hora  ·  " + value(next.precipitationProbability, 0, "%")
                + " de chance  ·  " + value(next.precipitation, 1, " mm");
        card.addView(label(nextLine, 11, Color.argb(230, 255, 255, 255), false),
                margins(-1, -2, 0, 9, 0, 0));
        String time = snapshot.currentTime.length() >= 16 ? snapshot.currentTime.substring(11, 16) : "agora";
        card.addView(label("Modelo atualizado às " + time, 10,
                Color.argb(195, 255, 255, 255), false), wrapWrap());
        return card;
    }

    private LinearLayout heroMetric(String title, String value) {
        LinearLayout metric = new LinearLayout(this);
        metric.setOrientation(LinearLayout.VERTICAL);
        metric.addView(label(title.toUpperCase(new Locale("pt", "BR")), 10,
                Color.argb(200, 255, 255, 255), true), wrapWrap());
        metric.addView(label(value, 15, Color.WHITE, true), margins(-1, -2, 0, 2, 0, 0));
        return metric;
    }

    private GradientDrawable weatherBackdrop(int weatherCode) {
        int[] colors;
        if (weatherCode >= 95) colors = new int[]{0xFF4D547C, 0xFF252E51};
        else if (weatherCode >= 51 && weatherCode <= 82) colors = new int[]{0xFF287D98, 0xFF24516D};
        else if (weatherCode == 3 || weatherCode == 45 || weatherCode == 48)
            colors = new int[]{0xFF69889A, 0xFF425E72};
        else colors = new int[]{0xFF2587AE, 0xFF23618B};
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        background.setCornerRadius(dp(23));
        return background;
    }

    private View buildFavoriteStrip() {
        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setHorizontalScrollBarEnabled(false);
        LinearLayout strip = new LinearLayout(this);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        if (devicePlace != null) {
            WeatherSnapshot localSnapshot = snapshots.get(devicePlace.key());
            strip.addView(placeChip(devicePlace, localSnapshot, "⌖"), margins(-2, dp(52), 0, 0, 7, 0));
        }
        for (Place favorite : favorites) {
            strip.addView(placeChip(favorite, snapshots.get(favorite.key()), "★"), margins(-2, dp(52), 0, 0, 7, 0));
        }
        horizontal.addView(strip, new HorizontalScrollView.LayoutParams(-2, -1));
        return horizontal;
    }

    private View placeChip(Place place, WeatherSnapshot snapshot, String prefix) {
        LinearLayout chip = new LinearLayout(this);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setOrientation(LinearLayout.VERTICAL);
        chip.setPadding(dp(11), dp(5), dp(11), dp(5));
        chip.setBackground(rounded(place.key().equals(activePlace == null ? "" : activePlace.key()) ? 0xFFDCEFF7 : Color.WHITE, 14));
        TextView top = label(prefix + "  " + place.name, 11, INK, true);
        top.setMaxLines(1);
        chip.addView(top, wrapWrap());
        String temp = snapshot == null ? "carregando…" : value(snapshot.currentTemperature, 0, "°")
                + "  ·  " + weatherIcon(snapshot.currentWeatherCode);
        chip.addView(label(temp, 10, MUTED, false), wrapWrap());
        chip.setOnClickListener(v -> selectPlace(place));
        chip.setOnLongClickListener(v -> {
            if (isFavorite(place)) confirmRemoveFavorite(place);
            return true;
        });
        return chip;
    }

    private View buildTabs() {
        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(dp(3), dp(3), dp(3), dp(3));
        tabs.setBackground(rounded(Color.rgb(226, 235, 239), 15));
        TextView today = tabLabel("Hoje", !dailyTab);
        TextView days = tabLabel("15 dias", dailyTab);
        tabs.addView(today, new LinearLayout.LayoutParams(0, dp(38), 1));
        tabs.addView(days, new LinearLayout.LayoutParams(0, dp(38), 1));
        today.setOnClickListener(v -> { dailyTab = false; selectedHour = -1; renderPage(); });
        days.setOnClickListener(v -> { dailyTab = true; selectedDay = -1; renderPage(); });
        return tabs;
    }

    private void buildHourlyForecast(WeatherSnapshot snapshot) {
        List<WeatherSnapshot.Hourly> hours = visibleHours(snapshot);
        page.addView(sectionHeading("Hora a hora", "Até o fim do dia local"), margins(-1, -2, 0, 0, 0, 3));
        page.addView(label("Temperatura °C · chuva mm · chance %", 11, MUTED, false), margins(-1, -2, 0, 0, 0, 4));
        if (hours.isEmpty()) {
            page.addView(infoCard("A API ainda não trouxe as horas restantes de hoje."), margins(-1, -2, 0, 0, 0, 8));
            return;
        }
        if (selectedHour < 0 || selectedHour >= hours.size()) selectedHour = 0;
        WeatherChartView chart = new WeatherChartView(this);
        int width = Math.max(getResources().getDisplayMetrics().widthPixels - dp(32), dp(40) + hours.size() * dp(54));
        chart.setHourly(hours, selectedHour);
        chart.setSelectionListener(index -> { selectedHour = index; renderPage(); });
        HorizontalScrollView chartScroller = chartScroller(chart, width, dp(210));
        page.addView(chartScroller, margins(-1, dp(210), 0, 0, 0, 5));
        WeatherSnapshot.Hourly selected = hours.get(selectedHour);
        page.addView(sectionHeading("Detalhes · " + shortTime(selected.time), "Previsão para esta hora"), margins(-1, -2, 0, 2, 0, 4));
        page.addView(metricRow(
                metric("Temperatura", value(selected.temperature, 0, "°"), "sensação " + value(selected.feelsLike, 0, "°")),
                metric("Umidade", value(selected.humidity, 0, "%"), "relativa")), margins(-1, -2, 0, 0, 0, 5));
        page.addView(metricRow(
                metric("Precipitação", value(selected.precipitation, 1, " mm"), "nesta hora"),
                metric("Probabilidade", value(selected.precipitationProbability, 0, "%"), "de precipitação")),
                margins(-1, -2, 0, 0, 0, 8));
        page.addView(buildHourlyStrip(hours), margins(-1, dp(104), 0, 0, 0, 8));
    }

    private void buildDailyForecast(WeatherSnapshot snapshot) {
        List<WeatherSnapshot.Daily> days = snapshot.daily;
        page.addView(sectionHeading("Próximos 15 dias", "Toque em um dia para abrir os detalhes"), margins(-1, -2, 0, 0, 0, 5));
        if (days.isEmpty()) {
            page.addView(infoCard("A previsão diária não está disponível agora."), margins(-1, -2, 0, 0, 0, 6));
            return;
        }
        if (selectedDay >= days.size()) selectedDay = -1;
        WeatherChartView chart = new WeatherChartView(this);
        int width = Math.max(getResources().getDisplayMetrics().widthPixels - dp(32), dp(40) + days.size() * dp(62));
        chart.setDaily(days, selectedDay);
        chart.setSelectionListener(index -> toggleDailyDay(index));
        page.addView(chartScroller(chart, width, dp(166)), margins(-1, dp(166), 0, 0, 0, 6));
        page.addView(buildDailyList(snapshot, days), margins(-1, -2, 0, 0, 0, 8));
    }

    private View buildHourlyStrip(List<WeatherSnapshot.Hourly> hours) {
        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        for (int i = 0; i < hours.size(); i++) {
            final int index = i;
            WeatherSnapshot.Hourly hour = hours.get(i);
            LinearLayout item = verticalCard(i == selectedHour ? PALE_BLUE : Color.WHITE);
            item.setPadding(dp(10), dp(9), dp(10), dp(9));
            item.setGravity(Gravity.CENTER);
            item.addView(label(shortTime(hour.time), 11, MUTED, true), wrapWrap());
            item.addView(label(weatherIcon(hour.weatherCode), 19, BLUE, false), margins(-2, -2, 2, 1, 0, 0));
            item.addView(label(value(hour.temperature, 0, "°"), 16, INK, true), wrapWrap());
            item.addView(label(value(hour.precipitation, 1, " mm"), 10, RAIN, true), wrapWrap());
            item.addView(label(value(hour.precipitationProbability, 0, "%"), 10, MUTED, false), wrapWrap());
            item.setOnClickListener(v -> { selectedHour = index; renderPage(); });
            row.addView(item, margins(dp(72), dp(96), 0, 0, 6, 0));
        }
        horizontal.addView(row, new HorizontalScrollView.LayoutParams(-2, -1));
        return horizontal;
    }

    private View buildDailyList(WeatherSnapshot snapshot, List<WeatherSnapshot.Daily> days) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        dailyForecastRows.clear();
        for (int i = 0; i < days.size(); i++) {
            final int index = i;
            WeatherSnapshot.Daily day = days.get(i);
            boolean expanded = i == selectedDay;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(dp(11), dp(3), dp(11), expanded ? dp(8) : dp(3));
            item.setBackground(rounded(expanded ? 0xFFE8F4F9 : Color.WHITE, 16));

            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(54));
            LinearLayout date = new LinearLayout(this);
            date.setOrientation(LinearLayout.VERTICAL);
            String weekday = i == 0 ? "Hoje" : shortWeekday(day.date);
            if (weekday.length() > 3 && i != 0) weekday = weekday.substring(0, 3);
            date.addView(label(weekday, 11, INK, true), wrapWrap());
            date.addView(label(compactDateLabel(day.date), 10, MUTED, false), wrapWrap());
            row.addView(date, new LinearLayout.LayoutParams(dp(62), -2));
            TextView icon = label(weatherIcon(day.weatherCode), 21, BLUE, false);
            icon.setGravity(Gravity.CENTER);
            row.addView(icon, margins(dp(30), dp(38), 0, 0, 4, 0));
            LinearLayout temps = new LinearLayout(this);
            temps.setOrientation(LinearLayout.VERTICAL);
            temps.setGravity(Gravity.CENTER_VERTICAL);
            TextView highLow = label(value(day.maxTemperature, 0, "°") + " / "
                    + value(day.minTemperature, 0, "°"), 13, INK, true);
            highLow.setMaxLines(1);
            temps.addView(highLow, wrapWrap());
            temps.addView(label("máx. / mín.", 10, MUTED, false), wrapWrap());
            row.addView(temps, new LinearLayout.LayoutParams(0, -2, 1));

            LinearLayout rain = new LinearLayout(this);
            rain.setOrientation(LinearLayout.VERTICAL);
            rain.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            rain.addView(label(value(day.precipitationProbability, 0, "%"), 12, RAIN, true), wrapWrap());
            rain.addView(label(value(day.precipitation, 1, " mm"), 10, MUTED, false), wrapWrap());
            row.addView(rain, new LinearLayout.LayoutParams(dp(52), -2));

            TextView disclosure = label(expanded ? "⌃" : "⌄", 18, BLUE, true);
            disclosure.setGravity(Gravity.CENTER);
            row.addView(disclosure, size(dp(22), dp(44)));
            row.setContentDescription((expanded ? "Fechar detalhes de " : "Ver detalhes de ")
                    + dateLabel(day.date) + ", máxima " + value(day.maxTemperature, 0, " graus")
                    + ", mínima " + value(day.minTemperature, 0, " graus")
                    + ", chuva " + value(day.precipitationProbability, 0, " por cento"));
            row.setOnClickListener(v -> toggleDailyDay(index));
            item.addView(row, matchWrap());
            if (expanded) item.addView(buildDailyDetails(snapshot, day), margins(-1, -2, 0, 1, 0, 0));
            dailyForecastRows.add(item);
            list.addView(item, margins(-1, -2, 0, 0, 0, 5));
        }
        return list;
    }

    private View buildDailyDetails(WeatherSnapshot snapshot, WeatherSnapshot.Daily day) {
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        View divider = new View(this);
        divider.setBackgroundColor(0xFFD2E5ED);
        details.addView(divider, margins(-1, dp(1), 0, 1, 0, 8));
        details.addView(dailyDetailRow(
                dailyDetail("Sensação térmica", value(day.minFeelsLike, 0, "°") + " / "
                        + value(day.maxFeelsLike, 0, "°")),
                dailyDetail("Umidade média", value(day.meanHumidity, 0, "%"))), margins(-1, -2, 0, 0, 0, 8));
        details.addView(dailyDetailRow(
                dailyDetail("Precipitação", value(day.precipitation, 1, " mm")),
                dailyDetail("Chance de chuva", value(day.precipitationProbability, 0, "%"))), matchWrap());
        details.addView(label(weatherIcon(day.weatherCode) + "  " + condition(day.weatherCode), 12, MUTED, false),
                margins(-1, -2, 0, 8, 0, 0));
        List<WeatherSnapshot.Hourly> hours = hoursForDate(snapshot, day.date);
        details.addView(sectionHeading("Previsão hora a hora",
                hours.size() + (hours.size() == 1 ? " horário · hora local" : " horários · hora local")),
                margins(-1, -2, 0, 6, 0, 5));
        if (hours.isEmpty()) {
            details.addView(label("Os horários deste dia não estão disponíveis.", 11, MUTED, false),
                    margins(-1, -2, 0, 0, 0, 3));
        } else {
            LinearLayout hourlyRows = new LinearLayout(this);
            hourlyRows.setOrientation(LinearLayout.VERTICAL);
            hourlyRows.setPadding(dp(8), dp(2), dp(8), dp(2));
            hourlyRows.setBackground(rounded(Color.WHITE, 13));
            for (int i = 0; i < hours.size(); i++) {
                hourlyRows.addView(dailyHourlyRow(hours.get(i)), matchWrap());
                if (i < hours.size() - 1) {
                    View line = new View(this);
                    line.setBackgroundColor(0xFFE8EFF2);
                    hourlyRows.addView(line, margins(-1, dp(1), dp(4), 0, dp(4), 0));
                }
            }
            details.addView(hourlyRows, margins(-1, -2, 0, 0, 0, 5));
        }
        return details;
    }

    private View dailyHourlyRow(WeatherSnapshot.Hourly hour) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(6), dp(2), dp(6));
        row.setMinimumHeight(dp(42));
        row.addView(label(shortTime(hour.time), 11, MUTED, true), size(dp(39), -2));
        TextView icon = label(weatherIcon(hour.weatherCode), 17, BLUE, false);
        icon.setGravity(Gravity.CENTER);
        row.addView(icon, margins(dp(24), dp(28), 0, 0, 5, 0));

        LinearLayout temperatures = new LinearLayout(this);
        temperatures.setOrientation(LinearLayout.VERTICAL);
        temperatures.addView(label(value(hour.temperature, 0, "°") + "  ·  sensação "
                + value(hour.feelsLike, 0, "°"), 11, INK, true), matchWrap());
        temperatures.addView(label("Umidade " + value(hour.humidity, 0, "%"), 10, MUTED, false), wrapWrap());
        row.addView(temperatures, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout rain = new LinearLayout(this);
        rain.setOrientation(LinearLayout.VERTICAL);
        rain.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        rain.addView(label(value(hour.precipitation, 1, " mm"), 11, RAIN, true), wrapWrap());
        rain.addView(label(value(hour.precipitationProbability, 0, "%") + " chuva", 10, MUTED, false), wrapWrap());
        row.addView(rain, new LinearLayout.LayoutParams(dp(66), -2));
        row.setContentDescription(shortTime(hour.time) + ", "
                + value(hour.temperature, 0, " graus") + ", sensação "
                + value(hour.feelsLike, 0, " graus") + ", umidade "
                + value(hour.humidity, 0, " por cento") + ", precipitação "
                + value(hour.precipitation, 1, " milímetros") + ", chance de chuva "
                + value(hour.precipitationProbability, 0, " por cento"));
        return row;
    }

    private List<WeatherSnapshot.Hourly> hoursForDate(WeatherSnapshot snapshot, String date) {
        List<WeatherSnapshot.Hourly> result = new ArrayList<>();
        String now = snapshot.currentTime;
        if (now == null || now.length() < 13) now = localNow(snapshot.timezone);
        String today = now.substring(0, 10);
        String currentHour = now.substring(0, 13);
        for (WeatherSnapshot.Hourly hour : snapshot.hourly) {
            if (hour.time.length() < 13 || !hour.time.startsWith(date)) continue;
            if (date.equals(today) && hour.time.substring(0, 13).compareTo(currentHour) < 0) continue;
            result.add(hour);
        }
        return result;
    }

    private View dailyDetailRow(View left, View right) {
        LinearLayout row = new LinearLayout(this);
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(right, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private View dailyDetail(String title, String value) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(label(title, 10, MUTED, false), wrapWrap());
        column.addView(label(value, 14, INK, true), margins(-1, -2, 0, 2, 0, 0));
        return column;
    }

    private void toggleDailyDay(int index) {
        selectedDay = selectedDay == index ? -1 : index;
        pendingDailyScroll = index;
        renderPage();
    }

    private View buildAttribution() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setGravity(Gravity.CENTER);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(label("Dados meteorológicos: Open-Meteo · Copernicus", 11, MUTED, false), wrapWrap());
        TextView about = label("Sobre os dados e privacidade", 11, BLUE, true);
        about.setPadding(0, dp(5), 0, 0);
        about.setOnClickListener(v -> showAbout());
        wrap.addView(about, wrapWrap());
        return wrap;
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("Sobre os dados")
                .setMessage("Previsões e condições atuais fornecidas pelo Open-Meteo, com modelos meteorológicos do Copernicus e de serviços meteorológicos nacionais. As condições atuais são derivadas de dados de modelo atualizados em intervalos de 15 minutos e podem diferir de uma estação próxima. A localização do aparelho e as cidades pesquisadas são enviadas ao Open-Meteo para consultar o clima. O app guarda localmente as cidades favoritas e a última resposta disponível.\n\nFonte: open-meteo.com · Geocodificação: GeoNames")
                .setPositiveButton("Entendi", null)
                .show();
    }

    private void beginLocationStartup() {
        if (hasLocationPermission()) {
            statusMessage = devicePlace == null ? "Localizando o aparelho…" : "Atualizando a previsão da sua localização…";
            renderPage();
            requestCurrentLocation();
        } else {
            statusMessage = "Permita o acesso à localização para mostrar o clima da sua cidade. Você também pode buscar um lugar.";
            renderPage();
            requestLocationPermission();
        }
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocationPermission() {
        if (permissionRequestPending) return;
        permissionRequestPending = true;
        preferences.edit().putBoolean("location_asked", true).apply();
        requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_LOCATION);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_LOCATION) return;
        permissionRequestPending = false;
        if (hasLocationPermission()) {
            statusMessage = "Localização permitida · buscando o clima atual…";
            renderPage();
            requestCurrentLocation();
        } else {
            statusMessage = "A localização foi negada. Escolha uma cidade pela busca para definir o clima inicial.";
            useFallbackPlace();
        }
    }

    private void requestCurrentLocation() {
        if (!hasLocationPermission()) { useFallbackPlace(); return; }
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager == null) { useFallbackPlace(); return; }
        locationFixReceived = false;
        Location last = bestLastKnownLocation();
        if (last != null) processLocation(last, false);
        locationTimeout = () -> {
            stopLocationUpdates();
            if (!locationFixReceived) {
                if (devicePlace == null) {
                    statusMessage = "Não consegui obter uma nova posição. Mostrando o último local salvo, se houver.";
                    useFallbackPlace();
                } else {
                    statusMessage = "Usando a última posição disponível. Confira se a localização está ativada.";
                    renderPage();
                }
            }
        };
        main.postDelayed(locationTimeout, 12000);
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                String provider = enabledProvider(LocationManager.NETWORK_PROVIDER);
                if (provider == null) provider = enabledProvider(LocationManager.GPS_PROVIDER);
                if (provider == null) { main.removeCallbacks(locationTimeout); useFallbackPlace(); return; }
                locationCancellation = new CancellationSignal();
                final String selectedProvider = provider;
                locationManager.getCurrentLocation(selectedProvider, locationCancellation, getMainExecutor(), location -> {
                    if (location != null) onFreshLocation(location);
                });
            } else {
                requestLegacyLocationUpdates();
            }
        } catch (RuntimeException error) {
            main.removeCallbacks(locationTimeout);
            if (devicePlace == null) useFallbackPlace();
        }
    }

    private String enabledProvider(String provider) {
        try { return locationManager.isProviderEnabled(provider) ? provider : null; }
        catch (RuntimeException e) { return null; }
    }

    @SuppressWarnings("MissingPermission")
    private Location bestLastKnownLocation() {
        Location best = null;
        for (String provider : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER}) {
            try {
                Location candidate = locationManager.getLastKnownLocation(provider);
                if (candidate != null && (best == null || candidate.getTime() > best.getTime())) best = candidate;
            } catch (RuntimeException ignored) { }
        }
        return best;
    }

    @SuppressWarnings("MissingPermission")
    private void requestLegacyLocationUpdates() {
        legacyLocationListener = new LocationListener() {
            @Override public void onLocationChanged(Location location) { onFreshLocation(location); }
            @Override public void onProviderDisabled(String provider) { }
            @Override public void onProviderEnabled(String provider) { }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
        };
        boolean requested = false;
        for (String provider : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER}) {
            try {
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.requestSingleUpdate(provider, legacyLocationListener, main.getLooper());
                    requested = true;
                }
            } catch (RuntimeException ignored) { }
        }
        if (!requested) {
            main.removeCallbacks(locationTimeout);
            useFallbackPlace();
        }
    }

    private void onFreshLocation(Location location) {
        if (location == null) return;
        locationFixReceived = true;
        main.removeCallbacks(locationTimeout);
        stopLocationUpdates();
        processLocation(location, true);
    }

    private void stopLocationUpdates() {
        if (locationTimeout != null) main.removeCallbacks(locationTimeout);
        if (locationCancellation != null) {
            try { locationCancellation.cancel(); } catch (RuntimeException ignored) { }
            locationCancellation = null;
        }
        if (locationManager != null && legacyLocationListener != null) {
            try { locationManager.removeUpdates(legacyLocationListener); } catch (RuntimeException ignored) { }
            legacyLocationListener = null;
        }
    }

    private void processLocation(Location location, boolean fresh) {
        worker.execute(() -> {
            Place place = nameFromLocation(location);
            main.post(() -> {
                if (isFinishing()) return;
                boolean changed = devicePlace == null || !devicePlace.key().equals(place.key());
                devicePlace = place;
                preferences.edit().putString("last_current", place.toJson().toString()).apply();
                if (!manualSelectionInSession && (activePlace == null || activePlace.currentLocation)) {
                    activePlace = place;
                    preferences.edit().putString("last_manual", "").apply();
                    persistWidgetPlace();
                }
                statusMessage = fresh ? "" : "Atualizando a posição atual…";
                renderPage();
                if (changed || snapshots.get(place.key()) == null || fresh && activePlace.currentLocation) {
                    refreshWeather(false);
                }
            });
        });
    }

    private Place nameFromLocation(Location location) {
        String name = "Minha localização", region = "", country = "";
        try {
            if (Geocoder.isPresent()) {
                Geocoder geocoder = new Geocoder(this, Locale.forLanguageTag("pt-BR"));
                List<Address> addresses = geocoder.getFromLocation(location.getLatitude(), location.getLongitude(), 1);
                if (addresses != null && !addresses.isEmpty()) {
                    Address address = addresses.get(0);
                    String locality = address.getLocality();
                    if (locality == null || locality.trim().isEmpty()) locality = address.getSubAdminArea();
                    if (locality == null || locality.trim().isEmpty()) locality = address.getAdminArea();
                    if (locality != null && !locality.trim().isEmpty()) name = locality.trim();
                    region = address.getAdminArea() == null ? "" : address.getAdminArea();
                    country = address.getCountryName() == null ? "" : address.getCountryName();
                }
            }
        } catch (Exception ignored) { }
        return new Place(name, region, country, "", location.getLatitude(), location.getLongitude(), "auto", true);
    }

    private void useFallbackPlace() {
        if (!hasLocationPermission()) {
            Place manual = readPlace("last_manual");
            if (manual != null) activePlace = manual;
        }
        if (activePlace == null) {
            Place manual = readPlace("last_manual");
            if (manual != null) activePlace = manual;
            else if (!favorites.isEmpty()) activePlace = favorites.get(0);
        }
        renderPage();
        if (activePlace != null && snapshots.get(activePlace.key()) == null) refreshWeather(false);
    }

    private void refreshWeather(boolean userInitiated) {
        if (refreshing) { refreshRequestedAgain = true; return; }
        if (activePlace == null) {
            statusMessage = "Escolha uma cidade pela busca para carregar a previsão.";
            renderPage();
            if (userInitiated) openCitySearch();
            return;
        }
        final List<Place> places = placesToLoad();
        if (places.isEmpty()) return;
        refreshing = true;
        if (userInitiated) statusMessage = "Atualizando condições e previsões…";
        renderPage();
        worker.execute(() -> {
            try {
                List<WeatherSnapshot> fetched = api.fetch(places);
                main.post(() -> {
                    if (isFinishing()) return;
                    for (WeatherSnapshot snapshot : fetched) {
                        snapshots.put(snapshot.place.key(), snapshot);
                        preferences.edit().putString(cacheKey(snapshot.place), snapshot.toJson().toString()).apply();
                        updatePlaceTimezone(snapshot.place);
                    }
                    WeatherWidgetProvider.refreshViews(MainActivity.this);
                    refreshing = false;
                    statusMessage = "";
                    renderPage();
                    if (refreshRequestedAgain) {
                        refreshRequestedAgain = false;
                        refreshWeather(false);
                    }
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (isFinishing()) return;
                    refreshing = false;
                    boolean hasCached = activePlace != null && snapshots.get(activePlace.key()) != null;
                    statusMessage = hasCached
                            ? "Sem conexão com a API. Exibindo a última previsão salva."
                            : "Não consegui carregar o clima. Confira sua conexão e tente atualizar.";
                    renderPage();
                    if (refreshRequestedAgain) {
                        refreshRequestedAgain = false;
                        refreshWeather(false);
                    }
                });
            }
        });
    }

    private List<Place> placesToLoad() {
        List<Place> places = new ArrayList<>();
        if (activePlace != null) places.add(activePlace);
        if (devicePlace != null && !containsPlace(places, devicePlace)) places.add(devicePlace);
        for (Place favorite : favorites) if (!containsPlace(places, favorite)) places.add(favorite);
        return places;
    }

    private boolean containsPlace(List<Place> places, Place candidate) {
        for (Place place : places) if (place.key().equals(candidate.key())) return true;
        return false;
    }

    private void updatePlaceTimezone(Place place) {
        if (devicePlace != null && devicePlace.key().equals(place.key())) devicePlace = devicePlace.withTimezone(place.timezone);
        if (activePlace != null && activePlace.key().equals(place.key())) activePlace = activePlace.withTimezone(place.timezone);
        for (int i = 0; i < favorites.size(); i++) {
            if (favorites.get(i).key().equals(place.key())) favorites.set(i, favorites.get(i).withTimezone(place.timezone));
        }
        if (devicePlace != null && devicePlace.key().equals(place.key())) {
            preferences.edit().putString("last_current", devicePlace.toJson().toString()).apply();
        }
        if (activePlace != null && activePlace.key().equals(place.key())) persistWidgetPlace();
        saveFavorites();
    }

    private void selectPlace(Place place) {
        if (place == null) return;
        int scroll = scrollView == null ? 0 : scrollView.getScrollY();
        activePlace = place;
        manualSelectionInSession = !place.currentLocation;
        dailyTab = false;
        selectedHour = -1;
        selectedDay = -1;
        if (!place.currentLocation) preferences.edit().putString("last_manual", place.toJson().toString()).apply();
        persistWidgetPlace();
        WeatherWidgetProvider.refreshViews(this);
        WeatherSnapshot cached = snapshots.get(place.key());
        if (cached == null) cached = readCachedSnapshot(place);
        if (cached != null) snapshots.put(place.key(), cached);
        statusMessage = cached == null ? "Carregando previsão de " + place.name + "…" : "";
        renderPage();
        if (cached == null) scroll = 0;
        final int target = scroll;
        scrollView.post(() -> scrollView.scrollTo(0, target));
        refreshWeather(false);
    }

    private void toggleFavorite(Place place) {
        if (place == null) { openCitySearch(); return; }
        if (isFavorite(place)) {
            confirmRemoveFavorite(place);
        } else {
            favorites.add(new Place(place.name, place.admin1, place.country, place.countryCode,
                    place.latitude, place.longitude, place.timezone, false));
            saveFavorites();
            statusMessage = "Cidade adicionada às favoritas.";
            renderPage();
            refreshWeather(false);
        }
    }

    private void confirmRemoveFavorite(Place place) {
        new AlertDialog.Builder(this)
                .setTitle("Remover favorita?")
                .setMessage("Remover " + place.name + " da sua lista?")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Remover", (dialog, which) -> {
                    for (int i = favorites.size() - 1; i >= 0; i--) {
                        if (favorites.get(i).key().equals(place.key())) favorites.remove(i);
                    }
                    saveFavorites();
                    renderPage();
                }).show();
    }

    private boolean isFavorite(Place place) {
        if (place == null) return false;
        for (Place favorite : favorites) if (favorite.key().equals(place.key())) return true;
        return false;
    }

    private void openCitySearch() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(6), dp(20), dp(4));
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Cidade, região ou país");
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        layout.addView(input, matchWrap());
        Button find = button("Buscar no mundo todo", true);
        layout.addView(find, margins(-1, dp(46), 0, 6, 0, 0));
        ProgressBar progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        layout.addView(progress, margins(-2, dp(36), 0, 10, 0, 4));
        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        layout.addView(results, matchWrap());
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Buscar cidade")
                .setView(layout)
                .setNegativeButton("Fechar", null)
                .create();
        find.setOnClickListener(v -> runCitySearch(input, find, progress, results, dialog));
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { runCitySearch(input, find, progress, results, dialog); return true; }
            return false;
        });
        dialog.show();
        input.requestFocus();
        input.postDelayed(() -> {
            InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (manager != null) manager.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }, 220);
    }

    private void runCitySearch(EditText input, Button find, ProgressBar progress,
                               LinearLayout results, AlertDialog dialog) {
        String query = input.getText().toString().trim();
        if (query.length() < 2) { input.setError("Digite pelo menos dois caracteres"); return; }
        InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (manager != null) manager.hideSoftInputFromWindow(input.getWindowToken(), 0);
        find.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        results.removeAllViews();
        worker.execute(() -> {
            try {
                List<Place> found = api.search(query);
                main.post(() -> {
                    if (isFinishing()) return;
                    progress.setVisibility(View.GONE);
                    find.setEnabled(true);
                    results.removeAllViews();
                    if (found.isEmpty()) {
                        results.addView(label("Nenhum lugar encontrado. Tente incluir o país, por exemplo: Paris, França.", 13, MUTED, false), margins(-1, -2, 0, 10, 0, 0));
                        return;
                    }
                    for (Place place : found) {
                        TextView result = label(place.name + "\n" + place.subtitle(), 14, INK, true);
                        result.setPadding(dp(12), dp(11), dp(12), dp(11));
                        result.setBackground(rounded(Color.WHITE, 12));
                        result.setOnClickListener(v -> { dialog.dismiss(); selectPlace(place); });
                        results.addView(result, margins(-1, -2, 0, 0, 0, 6));
                    }
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (isFinishing()) return;
                    progress.setVisibility(View.GONE);
                    find.setEnabled(true);
                    results.removeAllViews();
                    results.addView(label("A busca não respondeu. Confira a conexão e tente novamente.", 13, MUTED, false), margins(-1, -2, 0, 10, 0, 0));
                });
            }
        });
    }

    private void loadFavorites() {
        favorites.clear();
        try {
            JSONArray array = new JSONArray(preferences.getString("favorites", "[]"));
            for (int i = 0; i < array.length(); i++) {
                Place place = Place.fromJson(array.optJSONObject(i));
                if (place != null && !containsPlace(favorites, place)) favorites.add(place);
            }
        } catch (Exception ignored) { }
    }

    private void saveFavorites() {
        JSONArray array = new JSONArray();
        for (Place place : favorites) array.put(place.toJson());
        preferences.edit().putString("favorites", array.toString()).apply();
    }

    private Place readPlace(String key) {
        try {
            String json = preferences.getString(key, "");
            return json.isEmpty() ? null : Place.fromJson(new JSONObject(json));
        } catch (Exception e) { return null; }
    }

    private void loadCachedSnapshots() {
        List<Place> places = new ArrayList<>();
        if (activePlace != null) places.add(activePlace);
        if (devicePlace != null && !containsPlace(places, devicePlace)) places.add(devicePlace);
        for (Place favorite : favorites) if (!containsPlace(places, favorite)) places.add(favorite);
        for (Place place : places) {
            WeatherSnapshot snapshot = readCachedSnapshot(place);
            if (snapshot != null) snapshots.put(snapshot.place.key(), snapshot);
        }
    }

    private WeatherSnapshot readCachedSnapshot(Place place) {
        try {
            String json = preferences.getString(cacheKey(place), "");
            return json.isEmpty() ? null : WeatherSnapshot.fromJson(new JSONObject(json), place);
        } catch (Exception ignored) { return null; }
    }

    private String cacheKey(Place place) { return "weather_" + place.key().replace(',', '_'); }

    private void persistWidgetPlace() {
        if (preferences != null && activePlace != null) {
            preferences.edit().putString("widget_place", activePlace.toJson().toString()).apply();
        }
    }

    private void pinWeatherWidget() {
        if (activePlace == null) {
            Toast.makeText(this, "Escolha uma cidade antes de adicionar o widget.", Toast.LENGTH_SHORT).show();
            return;
        }
        persistWidgetPlace();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AppWidgetManager manager = AppWidgetManager.getInstance(this);
            if (manager.isRequestPinAppWidgetSupported()) {
                boolean requested = manager.requestPinAppWidget(
                        new ComponentName(this, WeatherWidgetProvider.class), null, null);
                Toast.makeText(this, requested
                        ? "Confirme a adição do widget na tela inicial."
                        : "Não foi possível abrir a confirmação do widget.", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        Toast.makeText(this, "Na tela inicial, abra Widgets e arraste o Tempo Agora.", Toast.LENGTH_LONG).show();
    }

    private List<WeatherSnapshot.Hourly> visibleHours(WeatherSnapshot snapshot) {
        List<WeatherSnapshot.Hourly> result = new ArrayList<>();
        String now = snapshot.currentTime;
        if (now == null || now.length() < 13) now = localNow(snapshot.timezone);
        String today = now.substring(0, 10);
        String currentHour = now.substring(0, 13);
        for (WeatherSnapshot.Hourly hour : snapshot.hourly) {
            if (hour.time.length() >= 13 && hour.time.substring(0, 10).equals(today)
                    && hour.time.substring(0, 13).compareTo(currentHour) >= 0) result.add(hour);
        }
        return result;
    }

    private String localNow(String timezone) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone(timezone));
        return format.format(new Date());
    }

    private String nextChance(WeatherSnapshot snapshot) {
        WeatherSnapshot.Hourly hour = nextHour(snapshot);
        return hour == null ? "—" : value(hour.precipitationProbability, 0, "%");
    }

    private String nextRain(WeatherSnapshot snapshot) {
        WeatherSnapshot.Hourly hour = nextHour(snapshot);
        return hour == null ? "sem previsão próxima" : value(hour.precipitation, 1, " mm na hora");
    }

    private WeatherSnapshot.Hourly nextHour(WeatherSnapshot snapshot) {
        String now = snapshot.currentTime;
        if (now == null || now.length() < 13) now = localNow(snapshot.timezone);
        String currentHour = now.substring(0, 13);
        for (WeatherSnapshot.Hourly hour : snapshot.hourly) {
            if (hour.time.length() >= 13 && hour.time.substring(0, 13).compareTo(currentHour) > 0) return hour;
        }
        return null;
    }

    private String shortTime(String iso) { return iso != null && iso.length() >= 16 ? iso.substring(11, 16) : "—"; }

    private String dateLabel(String iso) {
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Date date = in.parse(iso);
            SimpleDateFormat out = new SimpleDateFormat("EEE, d MMM", new Locale("pt", "BR"));
            return out.format(date);
        } catch (Exception e) { return iso; }
    }

    private String compactDateLabel(String iso) {
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Date date = in.parse(iso);
            return new SimpleDateFormat("d MMM", new Locale("pt", "BR")).format(date);
        } catch (Exception e) { return iso; }
    }

    private String shortWeekday(String iso) {
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Date date = in.parse(iso);
            SimpleDateFormat out = new SimpleDateFormat("EEEE", new Locale("pt", "BR"));
            String value = out.format(date);
            return value.substring(0, 1).toUpperCase(new Locale("pt", "BR")) + value.substring(1);
        } catch (Exception e) { return ""; }
    }

    private String value(double value, int decimals, String suffix) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return "—";
        NumberFormat format = NumberFormat.getNumberInstance(new Locale("pt", "BR"));
        format.setMinimumFractionDigits(decimals);
        format.setMaximumFractionDigits(decimals);
        return format.format(value) + suffix;
    }

    private String condition(int code) {
        if (code == 0) return "Céu limpo";
        if (code == 1) return "Predominantemente limpo";
        if (code == 2) return "Parcialmente nublado";
        if (code == 3) return "Nublado";
        if (code == 45 || code == 48) return "Neblina";
        if (code >= 51 && code <= 57) return "Garoa";
        if (code >= 61 && code <= 67) return "Chuva";
        if (code == 71 || code == 73 || code == 75 || code == 77) return "Neve";
        if (code >= 80 && code <= 82) return "Pancadas de chuva";
        if (code == 85 || code == 86) return "Pancadas de neve";
        if (code >= 95) return "Trovoadas";
        return "Condição variável";
    }

    private String weatherIcon(int code) {
        if (code == 0) return "☀";
        if (code == 1 || code == 2) return "🌤";
        if (code == 3) return "☁";
        if (code == 45 || code == 48) return "🌫";
        if (code >= 51 && code <= 67) return "🌧";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "❄";
        if (code >= 80 && code <= 82) return "🌦";
        if (code >= 95) return "⛈";
        return "☁";
    }

    private LinearLayout metric(String title, String value, String detail) {
        LinearLayout box = verticalCard(0xFFF6FAFC);
        box.setPadding(dp(10), dp(9), dp(10), dp(8));
        box.addView(label(title.toUpperCase(new Locale("pt", "BR")), 10, MUTED, true), wrapWrap());
        box.addView(label(value, 17, INK, true), margins(-1, -2, 0, 3, 0, 0));
        box.addView(label(detail, 10, MUTED, false), wrapWrap());
        return box;
    }

    private LinearLayout metricRow(View left, View right) {
        LinearLayout row = new LinearLayout(this);
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, -2, 1);
        rightParams.setMargins(dp(6), 0, 0, 0);
        row.addView(right, rightParams);
        return row;
    }

    private LinearLayout metricRow(View left, View middle, View right) {
        LinearLayout row = new LinearLayout(this);
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams middleParams = new LinearLayout.LayoutParams(0, -2, 1);
        middleParams.setMargins(dp(5), 0, dp(5), 0);
        row.addView(middle, middleParams);
        row.addView(right, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private View sectionHeading(String title, String subtitle) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(label(title, 16, INK, true), wrapWrap());
        column.addView(label(subtitle, 11, MUTED, false), margins(-1, -2, 0, 1, 0, 0));
        return column;
    }

    private TextView tabLabel(String title, boolean active) {
        TextView view = label(title, 14, active ? BLUE : MUTED, true);
        view.setGravity(Gravity.CENTER);
        if (active) view.setBackground(rounded(Color.WHITE, 11));
        return view;
    }

    private View infoCard(String message) {
        LinearLayout card = verticalCard(Color.WHITE);
        card.addView(label(message, 13, MUTED, false), matchWrap());
        return card;
    }

    private HorizontalScrollView chartScroller(View chart, int width, int height) {
        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setHorizontalScrollBarEnabled(false);
        horizontal.setFillViewport(false);
        horizontal.setBackground(rounded(Color.WHITE, 18));
        horizontal.addView(chart, new HorizontalScrollView.LayoutParams(width, height));
        return horizontal;
    }

    private Button button(String title, boolean primary) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setTextColor(primary ? Color.WHITE : INK);
        button.setBackground(rounded(primary ? BLUE : PALE_BLUE, 13));
        return button;
    }

    private TextView actionButton(String title, String description, Runnable action) {
        TextView button = label(title, 23, BLUE, true);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(description);
        button.setBackground(rounded(Color.WHITE, 14));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return view;
    }

    private LinearLayout verticalCard(int color) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(13), dp(11), dp(13), dp(11));
        card.setBackground(rounded(color, 18));
        return card;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private LinearLayout.LayoutParams wrapWrap() { return new LinearLayout.LayoutParams(-2, -2); }
    private LinearLayout.LayoutParams size(int width, int height) { return new LinearLayout.LayoutParams(width, height); }
    private LinearLayout.LayoutParams margins(int width, int height, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }
    private int topInScrollContent(View target) {
        int top = target.getTop();
        android.view.ViewParent parent = target.getParent();
        while (parent instanceof View && parent != scrollView) {
            View parentView = (View) parent;
            top += parentView.getTop();
            parent = parentView.getParent();
        }
        return top;
    }
    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    private void openAppLocationSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null));
            locationSettingsPending = true;
            startActivity(intent);
        } catch (RuntimeException ignored) { }
    }

    private final class RefreshScrollView extends ScrollView {
        private float downY;
        private boolean tracking;
        private Runnable listener;
        RefreshScrollView(Context context) { super(context); setClipToPadding(true); }
        void setOnPullRefreshListener(Runnable callback) { listener = callback; }
        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                downY = event.getY();
                tracking = getScrollY() == 0;
            } else if (event.getAction() == MotionEvent.ACTION_MOVE && tracking && getScrollY() == 0
                    && event.getY() - downY > dp(72)) {
                return true;
            } else if (event.getAction() == MotionEvent.ACTION_CANCEL) tracking = false;
            return super.onInterceptTouchEvent(event);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN && !tracking) { downY = event.getY(); tracking = getScrollY() == 0; }
            if (event.getAction() == MotionEvent.ACTION_UP && tracking && event.getY() - downY > dp(90)) {
                if (listener != null) listener.run();
                tracking = false;
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_CANCEL) tracking = false;
            return super.onTouchEvent(event);
        }
    }
}
