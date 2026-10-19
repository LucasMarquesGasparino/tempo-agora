package com.tempoagora;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
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
import android.view.WindowInsetsController;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public final class MainActivity extends Activity {
    /** Extra booleano: true quando a tela foi aberta tocando no widget. */
    static final String EXTRA_FROM_WIDGET = "com.tempoagora.extra.FROM_WIDGET";
    private static final int REQUEST_LOCATION = 410;
    private static final int REQUEST_EXPORT = 520;
    private static final int REQUEST_IMPORT = 521;
    private static final long MINUTE = 60_000L;
    private static final long MAX_BACKUP_BYTES = 1024L * 1024L;
    private static final long REFRESH_ON_NETWORK_MS = 10 * MINUTE;
    private static final long REFRESH_ON_RESUME_MS = 15 * MINUTE;
    private static final long REFRESH_RECENT_MS = 2 * MINUTE;
    private static final long LOCATION_STALE_MS = 30 * MINUTE;
    private static final long WARNING_AGE_MS = 120 * MINUTE;
    private static final int MENU_WIDGET_HERE = 1;
    private static final int MENU_PIN_WIDGET = 2;
    private static final int MENU_UNITS = 3;
    private static final int MENU_EXPORT = 4;
    private static final int MENU_IMPORT = 5;
    private static final int MENU_ABOUT = 6;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Place> favorites = new ArrayList<>();
    private final LinkedHashMap<String, WeatherSnapshot> snapshots = new LinkedHashMap<>();
    private final List<View> dailyRows = new ArrayList<>();
    private final List<View> hourlyStripItems = new ArrayList<>();
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) {
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (activePlace != null && dataAge(activePlace) > REFRESH_ON_NETWORK_MS && !refreshing) {
                    refreshWeather(false);
                }
            });
        }
    };

    private Palette palette;
    private Units units = Units.metric();
    private Place devicePlace;
    private Place activePlace;
    private RefreshScrollView scrollView;
    private LinearLayout page;
    private List<WeatherSnapshot.Hourly> hourlyVisible = new ArrayList<>();
    private List<WeatherSnapshot.Daily> dailyVisible = new ArrayList<>();
    private WeatherChartView hourlyChart;
    private LinearLayout hourlyDetails;
    private HorizontalScrollView hourlyStrip;
    private boolean dailyTab;
    private boolean tomorrowTab;
    private boolean refreshing;
    private boolean refreshRequestedAgain;
    private boolean manualSelectionInSession;
    private boolean locationFixReceived;
    private boolean permissionRequestPending;
    private boolean locationSettingsPending;
    private boolean networkRegistered;
    private String statusMessage = "";
    private int selectedHour = -1;
    private int selectedDay = -1;
    private int pendingDailyScroll = -1;
    private int searchSeq;
    private LocationManager locationManager;
    private LocationListener legacyLocationListener;
    private CancellationSignal locationCancellation;
    private Runnable locationTimeout;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        palette = Palette.of(this);
        units = AppPrefs.units(this);
        applySystemBars();
        restoreState(savedInstanceState);
        favorites.addAll(AppPrefs.favorites(this));
        Place device = AppPrefs.devicePlace(this);
        devicePlace = device == null ? null : device.asCurrentLocation();
        activePlace = resolveSelected();
        if (wasOpenedFromWidget(getIntent())) {
            Place widgetPlace = AppPrefs.widgetPlaceResolved(this);
            if (widgetPlace != null) activePlace = widgetPlace;
        }
        if (activePlace != null && AppPrefs.widgetPlace(this) == null) AppPrefs.setWidgetPlace(this, activePlace);
        buildShell();
        renderPage();
        loadCachedSnapshots();
        if (savedInstanceState == null) beginLocationStartup();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (wasOpenedFromWidget(intent)) {
            Place widgetPlace = AppPrefs.widgetPlaceResolved(this);
            if (widgetPlace != null) selectPlace(widgetPlace);
        }
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("dailyTab", dailyTab);
        outState.putBoolean("tomorrowTab", tomorrowTab);
        outState.putInt("selectedHour", selectedHour);
        outState.putInt("selectedDay", selectedDay);
        outState.putBoolean("manualSelectionInSession", manualSelectionInSession);
        outState.putBoolean("permissionRequestPending", permissionRequestPending);
    }

    @Override protected void onStart() {
        super.onStart();
        ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (manager == null) return;
        try {
            manager.registerDefaultNetworkCallback(networkCallback);
            networkRegistered = true;
        } catch (RuntimeException unavailable) {
            networkRegistered = false;
        }
    }

    @Override protected void onStop() {
        if (networkRegistered) {
            ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            try {
                if (manager != null) manager.unregisterNetworkCallback(networkCallback);
            } catch (RuntimeException ignored) {
                // Já estava desregistrado.
            }
            networkRegistered = false;
        }
        super.onStop();
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
        if (activePlace != null && dataAge(activePlace) > REFRESH_ON_RESUME_MS) refreshWeather(false);
        if (activePlace != null && activePlace.currentLocation && hasLocationPermission()
                && System.currentTimeMillis() - AppPrefs.lastFixAt(this) > LOCATION_STALE_MS) {
            requestCurrentLocation();
        }
    }

    @Override protected void onDestroy() {
        stopLocationUpdates();
        worker.shutdownNow();
        super.onDestroy();
    }

    private void restoreState(Bundle state) {
        if (state == null) return;
        dailyTab = state.getBoolean("dailyTab", false);
        tomorrowTab = state.getBoolean("tomorrowTab", false);
        selectedHour = state.getInt("selectedHour", -1);
        selectedDay = state.getInt("selectedDay", -1);
        manualSelectionInSession = state.getBoolean("manualSelectionInSession", false);
        permissionRequestPending = state.getBoolean("permissionRequestPending", false);
    }

    private boolean wasOpenedFromWidget(Intent intent) {
        return intent != null && intent.getBooleanExtra(EXTRA_FROM_WIDGET, false);
    }

    /** Roda em background, sem tocar a UI. Ignora se a tela já foi fechada. */
    private void background(Runnable task) {
        if (isFinishing() || isDestroyed() || worker.isShutdown()) return;
        try {
            worker.execute(task);
        } catch (RejectedExecutionException ignored) {
            // A atividade está sendo destruída.
        }
    }

    private void applySystemBars() {
        getWindow().setStatusBarColor(palette.background);
        getWindow().setNavigationBarColor(palette.background);
        if (Build.VERSION.SDK_INT >= 30) {
            int both = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            // getDecorView() instala o decor; Window.getInsetsController() antes de
            // setContentView dá NPE no Android 16.
            WindowInsetsController controller = getWindow().getDecorView().getWindowInsetsController();
            if (controller != null) controller.setSystemBarsAppearance(palette.dark ? 0 : both, both);
        } else {
            int flags = getWindow().getDecorView().getSystemUiVisibility();
            if (palette.dark) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    private void buildShell() {
        scrollView = new RefreshScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(palette.background);
        scrollView.setOnPullRefreshListener(() -> refreshWeather(true));
        scrollView.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(6), dp(16), dp(24));
        scrollView.addView(page, new ScrollView.LayoutParams(-1, -2));
        setContentView(scrollView);
        scrollView.post(() -> scrollView.requestApplyInsets());
    }

    private void renderPage() {
        if (page == null || isFinishing()) return;
        int oldScroll = scrollView.getScrollY();
        page.removeAllViews();
        dailyRows.clear();
        hourlyStripItems.clear();
        page.addView(buildToolbar(), matchWrap());
        page.addView(buildProgress(), margins(-1, dp(3), 0, 0, 0, 4));
        page.addView(buildPlaceHeader(), margins(-1, -2, 0, 0, 0, 7));

        if (!statusMessage.isEmpty()) page.addView(buildStatusCard(), margins(-1, -2, 0, 0, 0, 8));
        WeatherSnapshot snapshot = snapshotOf(activePlace);
        if (snapshot == null) page.addView(buildLoadingCard(), margins(-1, -2, 0, 0, 0, 9));
        else page.addView(buildCurrentCard(snapshot), margins(-1, -2, 0, 0, 0, 9));

        if (!favorites.isEmpty() || devicePlace != null) {
            page.addView(sectionHeading("Seus lugares", "Toque para ver · segure para remover"), margins(-1, -2, 0, 0, 0, 4));
            page.addView(buildFavoriteStrip(), margins(-1, dp(56), 0, 0, 0, 8));
        }

        page.addView(buildTabs(), margins(-1, dp(48), 0, 0, 0, 8));
        if (snapshot != null) {
            if (dailyTab) buildDailyForecast(snapshot);
            else buildHourlyForecast(snapshot);
        } else {
            page.addView(infoCard("A previsão aparece assim que uma cidade for carregada."), matchWrap());
        }
        page.addView(buildAttribution(), margins(-1, -2, 0, 12, 0, 4));
        scrollView.post(() -> {
            scrollView.scrollTo(0, Math.max(0, oldScroll));
            int index = pendingDailyScroll;
            pendingDailyScroll = -1;
            if (dailyTab && index >= 0 && index < dailyRows.size()) {
                int top = topInScrollContent(dailyRows.get(index));
                scrollView.smoothScrollTo(0, Math.max(0, top - dp(72)));
            }
        });
    }

    private View buildProgress() {
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setVisibility(refreshing ? View.VISIBLE : View.GONE);
        return bar;
    }

    private View buildToolbar() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, dp(2));
        row.addView(label("Atemporal", 19, palette.ink, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView search = actionText("Buscar", "Buscar cidade", v -> openCitySearch());
        search.setTextSize(13);
        search.setPadding(dp(12), 0, dp(12), 0);
        row.addView(search, margins(-2, dp(48), 0, 0, 6, 0));
        row.addView(actionButton("↻", "Atualizar clima", v -> refreshWeather(true)), margins(dp(48), dp(48), 0, 0, 6, 0));
        row.addView(actionButton("⋮", "Mais opções", this::showMenu), size(dp(48), dp(48)));
        return row;
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, MENU_WIDGET_HERE, 0, "Mostrar esta cidade no widget");
        menu.getMenu().add(0, MENU_PIN_WIDGET, 1, "Adicionar widget à tela inicial");
        menu.getMenu().add(0, MENU_UNITS, 2, "Unidades…");
        menu.getMenu().add(0, MENU_EXPORT, 3, "Exportar favoritos e ajustes");
        menu.getMenu().add(0, MENU_IMPORT, 4, "Importar backup");
        menu.getMenu().add(0, MENU_ABOUT, 5, "Sobre os dados");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case MENU_WIDGET_HERE: showWidgetHere(); break;
                case MENU_PIN_WIDGET: pinWeatherWidget(); break;
                case MENU_UNITS: showUnitsDialog(); break;
                case MENU_EXPORT: exportBackup(); break;
                case MENU_IMPORT: importBackup(); break;
                case MENU_ABOUT: showAbout(); break;
                default: return false;
            }
            return true;
        });
        menu.show();
    }

    private View buildPlaceHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        String name = activePlace == null ? "Escolha uma cidade" : activePlace.name;
        texts.addView(label(name, 21, palette.ink, true), wrapWrap());
        String subtitle = activePlace == null ? "Sua previsão começa aqui" : activePlace.subtitle();
        texts.addView(label(subtitle, 12, palette.muted, false), margins(-1, -2, 0, 1, 0, 0));
        if (activePlace != null && isWidgetPlace(activePlace)) {
            texts.addView(label("▣ No widget", 11, palette.accent, true), margins(-1, -2, 0, 2, 0, 0));
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        boolean favorite = activePlace != null && isFavorite(activePlace);
        String star = favorite ? "★" : "☆";
        String description = favorite ? "Remover dos favoritos" : "Favoritar cidade";
        row.addView(actionButton(star, description, v -> toggleFavorite(activePlace)), size(dp(48), dp(48)));
        return row;
    }

    private boolean isWidgetPlace(Place place) {
        Place widget = AppPrefs.widgetPlaceResolved(this);
        return widget != null && widget.sameKey(place);
    }

    private View buildStatusCard() {
        LinearLayout card = verticalCard(palette.expanded);
        card.addView(label(statusMessage, 13, palette.ink, false), matchWrap());
        if (!hasLocationPermission()) {
            Button location = button("Permitir localização", true);
            location.setOnClickListener(v -> {
                if (AppPrefs.locationAsked(this)
                        && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)
                        && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    openAppLocationSettings();
                } else {
                    requestLocationPermission();
                }
            });
            card.addView(location, margins(-1, dp(48), 0, 10, 0, 0));
        }
        if (activePlace == null) {
            Button search = button("Buscar uma cidade", true);
            search.setOnClickListener(v -> openCitySearch());
            card.addView(search, margins(-1, dp(48), 0, 10, 0, 0));
        }
        return card;
    }

    private View buildLoadingCard() {
        LinearLayout card = verticalCard(palette.surface);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(new ProgressBar(this), size(dp(30), dp(30)));
        row.addView(label(refreshing ? "Atualizando clima…" : "Buscando sua localização e previsão…",
                15, palette.ink, true), margins(-1, -2, 12, 0, 0, 0));
        card.addView(row, matchWrap());
        card.addView(label("Você também pode escolher uma cidade pelo botão de busca.", 12, palette.muted, false),
                margins(-1, -2, 0, 10, 0, 0));
        return card;
    }

    private View buildCurrentCard(WeatherSnapshot snapshot) {
        long nowMillis = System.currentTimeMillis();
        WeatherSnapshot.Now now = snapshot.now(nowMillis);
        WeatherSnapshot.Daily today = todayDaily(snapshot);
        String condition = WeatherCodes.condition(now.weatherCode);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(17), dp(15), dp(17), dp(13));
        card.setBackground(weatherBackdrop(now.weatherCode));
        card.setContentDescription("Agora em " + snapshot.place.name + ": " + Format.temperature(now.temperature, units)
                + ", " + condition + ", sensação " + Format.temperature(now.feelsLike, units)
                + ", umidade " + Format.percent(now.humidity)
                + ", vento " + windText(now.windSpeed, now.windDirection));

        LinearLayout hero = new LinearLayout(this);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout primary = new LinearLayout(this);
        primary.setOrientation(LinearLayout.VERTICAL);
        primary.addView(label(now.fromForecast ? "PREVISÃO PARA AGORA" : "AGORA", 10,
                Color.argb(210, 255, 255, 255), true), wrapWrap());
        primary.addView(label(Format.temperature(now.temperature, units), 56, Color.WHITE, true),
                margins(-1, -2, 0, -1, 0, 0));
        primary.addView(label(condition, 14, Color.WHITE, true), wrapWrap());
        if (today != null) {
            primary.addView(label("Máx " + Format.temperature(today.maxTemperature, units)
                    + "  ·  Mín " + Format.temperature(today.minTemperature, units), 12,
                    Color.argb(220, 255, 255, 255), false), margins(-1, -2, 0, 3, 0, 0));
        }
        hero.addView(primary, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView icon = weatherImage(now.weatherCode, now.isDay);
        icon.setPadding(dp(8), dp(8), dp(8), dp(8));
        icon.setBackground(rounded(Color.argb(235, 255, 255, 255), 22));
        hero.addView(icon, size(dp(72), dp(72)));
        card.addView(hero, matchWrap());

        View divider = new View(this);
        divider.setBackgroundColor(Color.argb(70, 255, 255, 255));
        card.addView(divider, margins(-1, dp(1), 0, 11, 0, 9));
        String compass = Format.compass(now.windDirection);
        card.addView(metricRow(
                heroMetric("Sensação", Format.temperature(now.feelsLike, units)),
                heroMetric("Umidade", Format.percent(now.humidity)),
                heroMetric("Vento" + (compass.isEmpty() ? "" : " " + compass), Format.wind(now.windSpeed, units))),
                matchWrap());

        WeatherSnapshot.Hourly next = nextHour(snapshot);
        String nextLine = next == null ? "Próxima hora · sem previsão disponível"
                : "Próxima hora  ·  " + Format.percent(next.precipitationProbability)
                + " de chance  ·  " + Format.precipitation(next.precipitation, units);
        card.addView(label(nextLine, 11, Color.argb(230, 255, 255, 255), false), margins(-1, -2, 0, 9, 0, 0));

        String sunUv = sunAndUvLine(today);
        if (!sunUv.isEmpty()) card.addView(label(sunUv, 11, Color.argb(230, 255, 255, 255), false), margins(-1, -2, 0, 3, 0, 0));

        long age = snapshot.fetchedAt > 0 ? nowMillis - snapshot.fetchedAt : -1;
        String updated = age >= 0 ? "Atualizado " + Format.relativeAge(age)
                : "Modelo das " + Format.clock(snapshot.currentTime);
        card.addView(label(updated, 10, Color.argb(195, 255, 255, 255), false), wrapWrap());
        if (age > WARNING_AGE_MS) {
            TextView warning = label("Dados de " + Format.relativeAge(age) + " · puxe para atualizar", 12,
                    palette.warningText, true);
            warning.setPadding(dp(10), dp(7), dp(10), dp(7));
            warning.setBackground(rounded(palette.warningBg, 10));
            card.addView(warning, margins(-1, -2, 0, 8, 0, 0));
        }
        return card;
    }

    private String sunAndUvLine(WeatherSnapshot.Daily today) {
        if (today == null) return "";
        List<String> parts = new ArrayList<>();
        if (!today.sunrise.isEmpty()) parts.add("Nascer " + Format.clock(today.sunrise));
        if (!today.sunset.isEmpty()) parts.add("Pôr " + Format.clock(today.sunset));
        if (!Double.isNaN(today.uvIndexMax)) {
            parts.add("UV máx. " + Format.number(today.uvIndexMax, 0, "") + " (" + Format.uvLabel(today.uvIndexMax) + ")");
        }
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (out.length() > 0) out.append(" · ");
            out.append(part);
        }
        return out.toString();
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
        int kind = WeatherCodes.kind(weatherCode);
        int[] colors;
        if (kind == WeatherCodes.KIND_THUNDER) {
            colors = palette.dark ? new int[]{0xFF2E3557, 0xFF1A2038} : new int[]{0xFF4D547C, 0xFF252E51};
        } else if (kind == WeatherCodes.KIND_DRIZZLE || kind == WeatherCodes.KIND_RAIN || kind == WeatherCodes.KIND_SHOWERS) {
            colors = palette.dark ? new int[]{0xFF1D5E78, 0xFF1A4256} : new int[]{0xFF287D98, 0xFF24516D};
        } else if (kind == WeatherCodes.KIND_CLOUDY || kind == WeatherCodes.KIND_FOG) {
            colors = palette.dark ? new int[]{0xFF4D6372, 0xFF2F4454} : new int[]{0xFF69889A, 0xFF425E72};
        } else {
            colors = palette.dark ? new int[]{0xFF1F6F90, 0xFF184A66} : new int[]{0xFF2587AE, 0xFF23618B};
        }
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
            strip.addView(placeChip(devicePlace, snapshotOf(devicePlace), "⌖", false), margins(-2, dp(52), 0, 0, 7, 0));
        }
        for (Place favorite : favorites) {
            strip.addView(placeChip(favorite, snapshotOf(favorite), "★", true), margins(-2, dp(52), 0, 0, 7, 0));
        }
        horizontal.addView(strip, new HorizontalScrollView.LayoutParams(-2, -1));
        return horizontal;
    }

    private View placeChip(Place place, WeatherSnapshot snapshot, String prefix, boolean favorite) {
        boolean selected = activePlace != null && place.sameKey(activePlace);
        LinearLayout chip = new LinearLayout(this);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setOrientation(LinearLayout.VERTICAL);
        chip.setPadding(dp(11), dp(5), dp(11), dp(5));
        chip.setMinimumHeight(dp(48));
        chip.setBackground(rounded(selected ? palette.selectedChip : palette.surface, 14));
        TextView top = label(prefix + "  " + place.name, 11, palette.ink, true);
        top.setMaxLines(1);
        chip.addView(top, wrapWrap());
        String temperature = snapshot == null ? "carregando…" : Format.temperature(snapshot.now(System.currentTimeMillis()).temperature, units);
        chip.addView(label(temperature, 10, palette.muted, false), wrapWrap());
        chip.setContentDescription(place.name + ", " + temperature + (favorite
                ? ". Toque para ver; toque e segure para remover dos favoritos" : ". Toque para ver"));
        chip.setOnClickListener(v -> selectPlace(place));
        chip.setOnLongClickListener(v -> {
            if (favorite && isFavorite(place)) confirmRemoveFavorite(place);
            return true;
        });
        return chip;
    }

    private View buildTabs() {
        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(dp(3), dp(3), dp(3), dp(3));
        tabs.setBackground(rounded(palette.tabTrack, 15));
        TextView today = tabLabel("Hoje", !dailyTab && !tomorrowTab);
        TextView tomorrow = tabLabel("Amanhã", tomorrowTab);
        TextView days = tabLabel("15 dias", dailyTab);
        tabs.addView(today, new LinearLayout.LayoutParams(0, dp(42), 1));
        tabs.addView(tomorrow, new LinearLayout.LayoutParams(0, dp(42), 1));
        tabs.addView(days, new LinearLayout.LayoutParams(0, dp(42), 1));
        today.setOnClickListener(v -> { dailyTab = false; tomorrowTab = false; selectedHour = -1; renderPage(); });
        tomorrow.setOnClickListener(v -> { dailyTab = false; tomorrowTab = true; selectedHour = -1; renderPage(); });
        days.setOnClickListener(v -> { dailyTab = true; tomorrowTab = false; selectedDay = -1; renderPage(); });
        return tabs;
    }

    private void buildHourlyForecast(WeatherSnapshot snapshot) {
        hourlyVisible = visibleHours(snapshot);
        List<WeatherSnapshot.Hourly> hours = hourlyVisible;
        page.addView(sectionHeading(tomorrowTab ? "Amanhã · hora a hora" : "Hora a hora",
                tomorrowTab ? "Previsão completa · hora local" : "Até o fim do dia local"),
                margins(-1, -2, 0, 0, 0, 3));
        page.addView(label("Temperatura " + units.temperatureSymbol() + " · chuva " + units.precipitationSymbol()
                + " · chance %", 11, palette.muted, false), margins(-1, -2, 0, 0, 0, 4));
        if (hours.isEmpty()) {
            page.addView(infoCard(tomorrowTab ? "A previsão por hora de amanhã não está disponível agora."
                    : "A API ainda não trouxe as horas restantes de hoje."), margins(-1, -2, 0, 0, 0, 8));
            return;
        }
        if (selectedHour < 0 || selectedHour >= hours.size()) selectedHour = 0;
        hourlyChart = new WeatherChartView(this);
        hourlyChart.setUnits(units);
        hourlyChart.setHourly(hours, selectedHour);
        hourlyChart.setSelectionListener(this::selectHour);
        int width = Math.max(getResources().getDisplayMetrics().widthPixels - dp(32), dp(40) + hours.size() * dp(54));
        page.addView(chartScroller(hourlyChart, width, dp(210)), margins(-1, dp(210), 0, 0, 0, 5));
        hourlyDetails = new LinearLayout(this);
        hourlyDetails.setOrientation(LinearLayout.VERTICAL);
        fillHourDetails(hours.get(selectedHour));
        page.addView(hourlyDetails, matchWrap());
        hourlyStrip = buildHourlyStrip(hours);
        page.addView(hourlyStrip, margins(-1, -2, 0, 8, 0, 8));
    }

    /** Troca a hora selecionada sem reconstruir a página. */
    private void selectHour(int index) {
        if (hourlyDetails == null || index < 0 || index >= hourlyVisible.size()) return;
        selectedHour = index;
        if (hourlyChart != null) hourlyChart.setSelectedIndex(index);
        fillHourDetails(hourlyVisible.get(index));
        for (int i = 0; i < hourlyStripItems.size(); i++) {
            hourlyStripItems.get(i).setBackground(rounded(i == index ? palette.paleAccent : palette.surface, 18));
        }
        if (hourlyStrip != null && index < hourlyStripItems.size()) {
            View item = hourlyStripItems.get(index);
            hourlyStrip.post(() -> hourlyStrip.smoothScrollTo(Math.max(0, item.getLeft() - dp(16)), 0));
        }
    }

    private void fillHourDetails(WeatherSnapshot.Hourly hour) {
        hourlyDetails.removeAllViews();
        hourlyDetails.addView(sectionHeading("Detalhes · " + Format.hourLabel(hour.time), "Previsão para esta hora"),
                margins(-1, -2, 0, 2, 0, 4));
        hourlyDetails.addView(metricRow(
                metric("Temperatura", Format.temperature(hour.temperature, units), "sensação " + Format.temperature(hour.feelsLike, units)),
                metric("Umidade", Format.percent(hour.humidity), "relativa")), margins(-1, -2, 0, 0, 0, 5));
        hourlyDetails.addView(metricRow(
                metric("Precipitação", Format.precipitation(hour.precipitation, units), "nesta hora"),
                metric("Probabilidade", Format.percent(hour.precipitationProbability), "de precipitação")),
                margins(-1, -2, 0, 0, 0, 5));
        String compass = Format.compass(hour.windDirection);
        hourlyDetails.addView(metricRow(
                metric("Vento", Format.wind(hour.windSpeed, units), compass.isEmpty() ? "direção —" : "direção " + compass),
                new View(this)), margins(-1, -2, 0, 0, 0, 8));
    }

    private HorizontalScrollView buildHourlyStrip(List<WeatherSnapshot.Hourly> hours) {
        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        for (int i = 0; i < hours.size(); i++) {
            final int index = i;
            WeatherSnapshot.Hourly hour = hours.get(i);
            String time = Format.hourLabel(hour.time);
            String temperature = Format.temperature(hour.temperature, units);
            String chance = Format.percent(hour.precipitationProbability);
            LinearLayout item = verticalCard(i == selectedHour ? palette.paleAccent : palette.surface);
            item.setPadding(dp(9), dp(5), dp(9), dp(5));
            item.setGravity(Gravity.CENTER);
            item.setMinimumHeight(dp(116));
            item.setContentDescription(time + ", " + temperature + ", chance de chuva " + chance);
            item.addView(label(time, 11, palette.muted, true), wrapWrap());
            item.addView(weatherImage(hour.weatherCode, hour.isDay), margins(dp(22), dp(22), 0, 3, 0, 3));
            item.addView(label(temperature, 16, palette.ink, true), wrapWrap());
            item.addView(label(Format.precipitation(hour.precipitation, units), 10, palette.rainText, true), wrapWrap());
            item.addView(label(chance, 10, palette.muted, false), wrapWrap());
            item.setOnClickListener(v -> selectHour(index));
            hourlyStripItems.add(item);
            row.addView(item, margins(dp(72), -2, 0, 0, 6, 0));
        }
        horizontal.addView(row, new HorizontalScrollView.LayoutParams(-2, -1));
        return horizontal;
    }

    private void buildDailyForecast(WeatherSnapshot snapshot) {
        dailyVisible = snapshot.upcomingDaily();
        page.addView(sectionHeading("Próximos 15 dias", "Toque em um dia para abrir os detalhes"), margins(-1, -2, 0, 0, 0, 5));
        if (dailyVisible.isEmpty()) {
            page.addView(infoCard("A previsão diária não está disponível agora."), margins(-1, -2, 0, 0, 0, 6));
            return;
        }
        if (selectedDay >= dailyVisible.size()) selectedDay = -1;
        WeatherChartView chart = new WeatherChartView(this);
        chart.setUnits(units);
        int width = Math.max(getResources().getDisplayMetrics().widthPixels - dp(32), dp(40) + dailyVisible.size() * dp(62));
        chart.setDaily(dailyVisible, selectedDay);
        chart.setSelectionListener(this::toggleDailyDay);
        page.addView(chartScroller(chart, width, dp(166)), margins(-1, dp(166), 0, 0, 0, 6));
        page.addView(buildDailyList(snapshot, dailyVisible), margins(-1, -2, 0, 0, 0, 8));
    }

    private View buildDailyList(WeatherSnapshot snapshot, List<WeatherSnapshot.Daily> days) {
        String today = snapshot.localToday();
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < days.size(); i++) {
            final int index = i;
            WeatherSnapshot.Daily day = days.get(i);
            boolean expanded = i == selectedDay;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(dp(11), dp(3), dp(11), expanded ? dp(8) : dp(3));
            item.setBackground(rounded(expanded ? palette.expanded : palette.surface, 16));

            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(54));
            String dayLabel = Format.dayLabel(day.date, today);
            boolean relative = dayLabel.startsWith("Hoje") || dayLabel.startsWith("Amanhã");
            LinearLayout date = new LinearLayout(this);
            date.setOrientation(LinearLayout.VERTICAL);
            date.addView(label(relative ? dayLabel : Format.weekdayShort(day.date), 11, palette.ink, true), wrapWrap());
            date.addView(label(Format.dayMonth(day.date), 10, palette.muted, false), wrapWrap());
            row.addView(date, new LinearLayout.LayoutParams(dp(62), -2));
            row.addView(weatherImage(day.weatherCode, true), size(dp(28), dp(28)));
            LinearLayout temps = new LinearLayout(this);
            temps.setOrientation(LinearLayout.VERTICAL);
            temps.setGravity(Gravity.CENTER_VERTICAL);
            TextView highLow = label(Format.temperature(day.maxTemperature, units) + " / "
                    + Format.temperature(day.minTemperature, units), 13, palette.ink, true);
            highLow.setMaxLines(1);
            temps.addView(highLow, wrapWrap());
            temps.addView(label("máx. / mín.", 10, palette.muted, false), wrapWrap());
            row.addView(temps, margins(0, -2, 6, 0, 0, 0));
            LinearLayout.LayoutParams tempsParams = (LinearLayout.LayoutParams) temps.getLayoutParams();
            tempsParams.weight = 1;

            LinearLayout rain = new LinearLayout(this);
            rain.setOrientation(LinearLayout.VERTICAL);
            rain.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            rain.addView(label(Format.percent(day.precipitationProbability), 12, palette.rainText, true), wrapWrap());
            rain.addView(label(Format.precipitation(day.precipitation, units), 10, palette.muted, false), wrapWrap());
            row.addView(rain, new LinearLayout.LayoutParams(dp(60), -2));

            TextView disclosure = label(expanded ? "⌃" : "⌄", 18, palette.accent, true);
            disclosure.setGravity(Gravity.CENTER);
            row.addView(disclosure, size(dp(22), dp(48)));
            row.setContentDescription((expanded ? "Fechar detalhes de " : "Ver detalhes de ")
                    + Format.longDate(day.date) + ", máxima " + Format.temperature(day.maxTemperature, units)
                    + ", mínima " + Format.temperature(day.minTemperature, units)
                    + ", chuva " + Format.percent(day.precipitationProbability));
            row.setOnClickListener(v -> toggleDailyDay(index));
            item.addView(row, matchWrap());
            if (expanded) item.addView(buildDailyDetails(snapshot, day), margins(-1, -2, 0, 1, 0, 0));
            dailyRows.add(item);
            list.addView(item, margins(-1, -2, 0, 0, 0, 5));
        }
        return list;
    }

    private View buildDailyDetails(WeatherSnapshot snapshot, WeatherSnapshot.Daily day) {
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        View divider = new View(this);
        divider.setBackgroundColor(palette.divider);
        details.addView(divider, margins(-1, dp(1), 0, 1, 0, 8));
        details.addView(dailyDetailRow(
                dailyDetail("Sensação térmica", Format.temperature(day.minFeelsLike, units) + " / "
                        + Format.temperature(day.maxFeelsLike, units)),
                dailyDetail("Umidade média", Format.percent(day.meanHumidity))), margins(-1, -2, 0, 0, 0, 8));
        details.addView(dailyDetailRow(
                dailyDetail("Precipitação", Format.precipitation(day.precipitation, units)),
                dailyDetail("Chance de chuva", Format.percent(day.precipitationProbability))), margins(-1, -2, 0, 0, 0, 8));
        details.addView(dailyDetailRow(
                dailyDetail("Vento máx.", Format.wind(day.windSpeedMax, units)),
                dailyDetail("UV máx.", Format.number(day.uvIndexMax, 0, "")
                        + (Double.isNaN(day.uvIndexMax) ? "" : " · " + Format.uvLabel(day.uvIndexMax)))),
                margins(-1, -2, 0, 0, 0, 8));
        String sun = "Nascer " + Format.clock(day.sunrise) + " · Pôr do sol " + Format.clock(day.sunset);
        details.addView(label(sun, 12, palette.muted, false), margins(-1, -2, 0, 0, 0, 4));
        details.addView(label(WeatherCodes.condition(day.weatherCode), 12, palette.muted, false), margins(-1, -2, 0, 0, 0, 0));
        List<WeatherSnapshot.Hourly> hours = hoursForDate(snapshot, day.date);
        details.addView(sectionHeading("Previsão hora a hora",
                hours.size() + (hours.size() == 1 ? " horário · hora local" : " horários · hora local")),
                margins(-1, -2, 0, 6, 0, 5));
        if (hours.isEmpty()) {
            details.addView(label("Os horários deste dia não estão disponíveis.", 11, palette.muted, false),
                    margins(-1, -2, 0, 0, 0, 3));
        } else {
            LinearLayout hourlyRows = new LinearLayout(this);
            hourlyRows.setOrientation(LinearLayout.VERTICAL);
            hourlyRows.setPadding(dp(8), dp(2), dp(8), dp(2));
            hourlyRows.setBackground(rounded(palette.surface, 13));
            for (int i = 0; i < hours.size(); i++) {
                hourlyRows.addView(dailyHourlyRow(hours.get(i)), matchWrap());
                if (i < hours.size() - 1) {
                    View line = new View(this);
                    line.setBackgroundColor(palette.divider);
                    hourlyRows.addView(line, margins(-1, dp(1), 4, 0, 4, 0));
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
        row.setMinimumHeight(dp(44));
        row.addView(label(Format.hourLabel(hour.time), 11, palette.muted, true), size(dp(39), -2));
        row.addView(weatherImage(hour.weatherCode, hour.isDay), size(dp(22), dp(22)));

        LinearLayout temperatures = new LinearLayout(this);
        temperatures.setOrientation(LinearLayout.VERTICAL);
        temperatures.addView(label(Format.temperature(hour.temperature, units) + "  ·  sensação "
                + Format.temperature(hour.feelsLike, units), 11, palette.ink, true), matchWrap());
        temperatures.addView(label("Umidade " + Format.percent(hour.humidity), 10, palette.muted, false), wrapWrap());
        row.addView(temperatures, margins(0, -2, 6, 0, 0, 0));
        ((LinearLayout.LayoutParams) temperatures.getLayoutParams()).weight = 1;

        LinearLayout rain = new LinearLayout(this);
        rain.setOrientation(LinearLayout.VERTICAL);
        rain.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        rain.addView(label(Format.precipitation(hour.precipitation, units), 11, palette.rainText, true), wrapWrap());
        rain.addView(label(Format.percent(hour.precipitationProbability) + " chuva", 10, palette.muted, false), wrapWrap());
        row.addView(rain, new LinearLayout.LayoutParams(dp(74), -2));
        row.setContentDescription(Format.hourLabel(hour.time) + ", " + Format.temperature(hour.temperature, units)
                + ", sensação " + Format.temperature(hour.feelsLike, units)
                + ", umidade " + Format.percent(hour.humidity)
                + ", chuva " + Format.precipitation(hour.precipitation, units)
                + ", chance de chuva " + Format.percent(hour.precipitationProbability));
        return row;
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
        column.addView(label(title, 10, palette.muted, false), wrapWrap());
        column.addView(label(value, 14, palette.ink, true), margins(-1, -2, 0, 2, 0, 0));
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
        wrap.addView(label("Dados meteorológicos: Open-Meteo · Copernicus", 11, palette.muted, false), wrapWrap());
        TextView about = label("Sobre os dados e privacidade", 11, palette.accent, true);
        about.setPadding(0, dp(5), 0, 0);
        about.setOnClickListener(v -> showAbout());
        wrap.addView(about, wrapWrap());
        return wrap;
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("Sobre os dados")
                .setMessage("Previsões e condições atuais fornecidas pelo Open-Meteo, com modelos meteorológicos do Copernicus e de serviços meteorológicos nacionais. As condições atuais são derivadas de dados de modelo atualizados em intervalos de 15 minutos e podem diferir de uma estação próxima. A localização do aparelho e as cidades pesquisadas são enviadas ao Open-Meteo para consultar o clima.\n\nO app guarda no aparelho as cidades favoritas, os ajustes e a última previsão recebida (o cache não sai do aparelho). Favoritos e ajustes podem ser exportados e importados pelo menu ⋮.\n\nFonte: open-meteo.com · Geocodificação: GeoNames")
                .setPositiveButton("Entendi", null)
                .show();
    }

    private void beginLocationStartup() {
        if (hasLocationPermission()) {
            if (activePlace == null || activePlace.currentLocation) {
                if (devicePlace == null) statusMessage = "Localizando o aparelho…";
                renderPage();
                if (devicePlace == null || System.currentTimeMillis() - AppPrefs.lastFixAt(this) > LOCATION_STALE_MS) {
                    requestCurrentLocation();
                }
            }
            return;
        }
        boolean manualChoice = AppPrefs.selectedPlace(this) != null && !AppPrefs.selectedPlace(this).currentLocation;
        if (!AppPrefs.locationAsked(this) && !manualChoice) {
            statusMessage = "Permita o acesso à localização para mostrar o clima da sua cidade. Você também pode buscar um lugar.";
            renderPage();
            requestLocationPermission();
        } else {
            renderPage();
        }
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocationPermission() {
        if (permissionRequestPending) return;
        permissionRequestPending = true;
        AppPrefs.setLocationAsked(this);
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
                String provider = null;
                if (Build.VERSION.SDK_INT >= 31 && fusedAvailable()) provider = LocationManager.FUSED_PROVIDER;
                if (provider == null) provider = enabledProvider(LocationManager.NETWORK_PROVIDER);
                if (provider == null) provider = enabledProvider(LocationManager.GPS_PROVIDER);
                if (provider == null) { main.removeCallbacks(locationTimeout); useFallbackPlace(); return; }
                locationCancellation = new CancellationSignal();
                locationManager.getCurrentLocation(provider, locationCancellation, getMainExecutor(), location -> {
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

    private boolean fusedAvailable() {
        try {
            return locationManager.hasProvider(LocationManager.FUSED_PROVIDER)
                    && locationManager.isProviderEnabled(LocationManager.FUSED_PROVIDER);
        } catch (RuntimeException unavailable) {
            return false;
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
        final Place previous = devicePlace;
        background(() -> {
            Place place = nameFromLocation(location, previous);
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                boolean changed = previous == null || !previous.sameKey(place);
                devicePlace = place;
                AppPrefs.setDevicePlace(this, place);
                if (fresh) AppPrefs.setLastFixAt(this, System.currentTimeMillis());
                if (!manualSelectionInSession && (activePlace == null || activePlace.currentLocation)) {
                    activePlace = place;
                    AppPrefs.setSelectedPlace(this, place);
                }
                if (AppPrefs.widgetPlace(this) == null) AppPrefs.setWidgetPlace(this, place);
                statusMessage = fresh ? "" : "Atualizando a posição atual…";
                renderPage();
                if (changed || snapshotOf(activePlace) == null
                        || (fresh && activePlace != null && activePlace.currentLocation)) {
                    refreshWeather(false);
                }
                WidgetUpdater.onPlaceOrDataChanged(this);
            });
        });
    }

    /** Nome do lugar pelo geocoder. Se falhar e a posição não mudou, mantém o nome anterior. */
    private Place nameFromLocation(Location location, Place previous) {
        String name = "";
        String region = "";
        String country = "";
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
        } catch (IOException | IllegalArgumentException ignored) {
            // Sem geocoder: usa o nome genérico ou o anterior.
        }
        double latitude = location.getLatitude();
        double longitude = location.getLongitude();
        boolean named = !name.isEmpty();
        Place place = Place.device(named ? name : "Minha localização", region, country, latitude, longitude);
        if (!named && previous != null && previous.sameKey(place)) {
            return Place.device(previous.name, previous.admin1, previous.country, latitude, longitude);
        }
        return place;
    }

    private void useFallbackPlace() {
        if (activePlace == null) activePlace = resolveSelected();
        if (activePlace == null) {
            statusMessage = "Escolha uma cidade pela busca para carregar a previsão.";
            renderPage();
            return;
        }
        renderPage();
        if (snapshotOf(activePlace) == null) refreshWeather(false);
    }

    /** Lugar que o app abre: a última escolha manual; senão a localização; senão o primeiro favorito. */
    private Place resolveSelected() {
        Place selected = AppPrefs.selectedPlace(this);
        if (selected != null && !selected.currentLocation) return selected;
        if (devicePlace != null) return devicePlace;
        return favorites.isEmpty() ? null : favorites.get(0);
    }

    private void loadCachedSnapshots() {
        final List<Place> places = placesToLoad();
        background(() -> {
            List<WeatherSnapshot> loaded = new ArrayList<>();
            for (Place place : places) {
                WeatherSnapshot snapshot = WeatherCache.read(this, place);
                if (snapshot != null) loaded.add(snapshot);
            }
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                for (WeatherSnapshot snapshot : loaded) snapshots.put(snapshot.place.key(), snapshot);
                renderPage();
                if (activePlace != null && dataAge(activePlace) > REFRESH_ON_RESUME_MS) refreshWeather(false);
            });
        });
    }

    private void refreshWeather(boolean userInitiated) {
        if (refreshing) { refreshRequestedAgain = true; return; }
        if (activePlace == null) {
            statusMessage = "Escolha uma cidade pela busca para carregar a previsão.";
            renderPage();
            if (userInitiated) openCitySearch();
            return;
        }
        if (!NetworkStatus.isOnline(this)) {
            WeatherSnapshot cached = snapshotOf(activePlace);
            statusMessage = cached == null ? "Sem internet. Conecte-se para carregar a previsão."
                    : "Sem internet · mostrando dados de " + Format.relativeAge(dataAge(activePlace)) + ".";
            renderPage();
            return;
        }
        final List<Place> places = placesToLoad();
        if (places.isEmpty()) return;
        refreshing = true;
        if (userInitiated) statusMessage = "Atualizando condições e previsões…";
        renderPage();
        final List<Place> keep = new ArrayList<>(places);
        Place widgetPlace = AppPrefs.widgetPlace(this);
        if (widgetPlace != null) keep.add(widgetPlace);
        background(() -> {
            try {
                List<WeatherSnapshot> fetched = WeatherRepository.refresh(this, places);
                WeatherCache.prune(this, keep);
                AppPrefs.removeLegacyCache(this);
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    for (WeatherSnapshot snapshot : fetched) {
                        snapshots.put(snapshot.place.key(), snapshot);
                        updatePlaceTimezone(snapshot.place);
                    }
                    refreshing = false;
                    statusMessage = "";
                    renderPage();
                    WidgetUpdater.onPlaceOrDataChanged(this);
                    finishRefreshRequest();
                });
            } catch (WeatherApiClient.ApiException error) {
                final String reason = error.userMessage();
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    refreshing = false;
                    WeatherSnapshot cached = snapshotOf(activePlace);
                    statusMessage = cached == null ? reason
                            : reason + " Mostrando dados de " + Format.relativeAge(dataAge(activePlace)) + ".";
                    renderPage();
                    finishRefreshRequest();
                });
            }
        });
    }

    /** Refaz a busca só se alguma cidade ficou sem dados recentes durante a busca anterior. */
    private void finishRefreshRequest() {
        if (!refreshRequestedAgain) return;
        refreshRequestedAgain = false;
        for (Place place : placesToLoad()) {
            if (dataAge(place) > REFRESH_RECENT_MS) {
                refreshWeather(false);
                return;
            }
        }
    }

    private WeatherSnapshot snapshotOf(Place place) {
        return place == null ? null : snapshots.get(place.key());
    }

    /** Idade dos dados do lugar em millis; enorme se não há dados. */
    private long dataAge(Place place) {
        WeatherSnapshot snapshot = snapshotOf(place);
        if (snapshot == null || snapshot.fetchedAt <= 0) return Long.MAX_VALUE;
        return System.currentTimeMillis() - snapshot.fetchedAt;
    }

    private List<Place> placesToLoad() {
        List<Place> places = new ArrayList<>();
        if (activePlace != null) places.add(activePlace);
        if (devicePlace != null && !containsPlace(places, devicePlace)) places.add(devicePlace);
        for (Place favorite : favorites) if (!containsPlace(places, favorite)) places.add(favorite);
        return places;
    }

    private boolean containsPlace(List<Place> places, Place candidate) {
        for (Place place : places) if (place.sameKey(candidate)) return true;
        return false;
    }

    private void updatePlaceTimezone(Place place) {
        if (devicePlace != null && devicePlace.sameKey(place)) {
            devicePlace = devicePlace.withTimezone(place.timezone);
            AppPrefs.setDevicePlace(this, devicePlace);
        }
        if (activePlace != null && activePlace.sameKey(place)) activePlace = activePlace.withTimezone(place.timezone);
        for (int i = 0; i < favorites.size(); i++) {
            if (favorites.get(i).sameKey(place)) favorites.set(i, favorites.get(i).withTimezone(place.timezone));
        }
        saveFavorites();
    }

    private void selectPlace(Place place) {
        if (place == null) return;
        int scroll = scrollView == null ? 0 : scrollView.getScrollY();
        activePlace = place;
        manualSelectionInSession = !place.currentLocation;
        dailyTab = false;
        tomorrowTab = false;
        selectedHour = -1;
        selectedDay = -1;
        AppPrefs.setSelectedPlace(this, place);
        WeatherSnapshot cached = snapshotOf(place);
        statusMessage = cached == null ? "Carregando previsão de " + place.name + "…" : "";
        renderPage();
        if (cached == null) {
            background(() -> {
                WeatherSnapshot stored = WeatherCache.read(this, place);
                main.post(() -> {
                    if (isFinishing() || isDestroyed() || stored == null) return;
                    if (activePlace != null && activePlace.sameKey(place) && snapshotOf(place) == null) {
                        snapshots.put(stored.place.key(), stored);
                        renderPage();
                    }
                });
            });
        }
        scrollView.post(() -> scrollView.scrollTo(0, cached == null ? 0 : scroll));
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
                        if (favorites.get(i).sameKey(place)) favorites.remove(i);
                    }
                    saveFavorites();
                    renderPage();
                    WidgetUpdater.onPlaceOrDataChanged(this);
                }).show();
    }

    private boolean isFavorite(Place place) {
        if (place == null) return false;
        for (Place favorite : favorites) if (favorite.sameKey(place)) return true;
        return false;
    }

    private void saveFavorites() {
        AppPrefs.saveFavorites(this, favorites);
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
        layout.addView(find, margins(-1, dp(48), 0, 6, 0, 0));
        TextView useHere = label("⌖  Usar minha localização atual", 14, palette.accent, true);
        useHere.setGravity(Gravity.CENTER);
        useHere.setMinHeight(dp(48));
        useHere.setBackground(rounded(palette.paleAccent, 13));
        layout.addView(useHere, margins(-1, -2, 0, 10, 0, 4));
        ProgressBar progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        layout.addView(progress, margins(-2, dp(36), 0, 10, 0, 4));
        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        layout.addView(results, matchWrap());
        ScrollView root = new ScrollView(this);
        root.addView(layout);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Buscar cidade")
                .setView(root)
                .setNegativeButton("Fechar", null)
                .create();
        useHere.setOnClickListener(v -> {
            dialog.dismiss();
            useCurrentLocationFromSearch();
        });
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

    private void useCurrentLocationFromSearch() {
        if (hasLocationPermission()) {
            if (devicePlace != null) selectPlace(devicePlace);
            requestCurrentLocation();
        } else if (AppPrefs.locationAsked(this)
                && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)
                && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
            openAppLocationSettings();
        } else {
            requestLocationPermission();
        }
    }

    private void runCitySearch(EditText input, Button find, ProgressBar progress,
                               LinearLayout results, AlertDialog dialog) {
        String query = input.getText().toString().trim();
        if (query.length() < 2) { input.setError("Digite pelo menos dois caracteres"); return; }
        InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (manager != null) manager.hideSoftInputFromWindow(input.getWindowToken(), 0);
        results.removeAllViews();
        if (!NetworkStatus.isOnline(this)) {
            results.addView(label("Sem internet para buscar cidades.", 13, palette.muted, false), margins(-1, -2, 0, 10, 0, 0));
            return;
        }
        final int sequence = ++searchSeq;
        find.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        background(() -> {
            try {
                List<Place> found = new WeatherApiClient().search(query);
                main.post(() -> {
                    if (isFinishing() || isDestroyed() || sequence != searchSeq) return;
                    progress.setVisibility(View.GONE);
                    find.setEnabled(true);
                    results.removeAllViews();
                    if (found.isEmpty()) {
                        results.addView(label("Nenhum lugar encontrado. Tente incluir o país, por exemplo: Paris, França.", 13, palette.muted, false), margins(-1, -2, 0, 10, 0, 0));
                        return;
                    }
                    for (Place place : found) {
                        final Place chosen = place;
                        TextView result = label(place.name + "\n" + place.subtitle(), 14, palette.ink, true);
                        result.setPadding(dp(12), dp(11), dp(12), dp(11));
                        result.setMinHeight(dp(48));
                        result.setBackground(rounded(palette.surface, 12));
                        result.setOnClickListener(v -> { dialog.dismiss(); selectPlace(chosen); });
                        results.addView(result, margins(-1, -2, 0, 0, 0, 6));
                    }
                });
            } catch (WeatherApiClient.ApiException error) {
                final String reason = error.userMessage();
                main.post(() -> {
                    if (isFinishing() || isDestroyed() || sequence != searchSeq) return;
                    progress.setVisibility(View.GONE);
                    find.setEnabled(true);
                    results.removeAllViews();
                    results.addView(label("A busca não respondeu. " + reason, 13, palette.muted, false), margins(-1, -2, 0, 10, 0, 0));
                });
            }
        });
    }

    private void showWidgetHere() {
        if (activePlace == null) {
            Toast.makeText(this, "Escolha uma cidade antes.", Toast.LENGTH_SHORT).show();
            return;
        }
        AppPrefs.setWidgetPlace(this, activePlace);
        Toast.makeText(this, "O widget agora mostra " + activePlace.name + ".", Toast.LENGTH_SHORT).show();
        WidgetUpdater.onPlaceOrDataChanged(this);
        renderPage();
    }

    private void showUnitsDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(6), dp(20), dp(4));
        RadioGroup temperature = unitGroup(layout, "Temperatura", new String[]{"°C", "°F"}, 100,
                Units.TEMP_F.equals(units.temperature) ? 1 : 0);
        RadioGroup wind = unitGroup(layout, "Vento", new String[]{"km/h", "m/s"}, 200,
                Units.WIND_MS.equals(units.wind) ? 1 : 0);
        RadioGroup precipitation = unitGroup(layout, "Chuva", new String[]{"mm", "pol"}, 300,
                Units.PRECIP_IN.equals(units.precipitation) ? 1 : 0);
        new AlertDialog.Builder(this)
                .setTitle("Unidades")
                .setView(layout)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Salvar", (dialog, which) -> {
                    Units next = new Units(
                            temperature.getCheckedRadioButtonId() == 101 + 1 ? Units.TEMP_F : Units.TEMP_C,
                            wind.getCheckedRadioButtonId() == 201 + 1 ? Units.WIND_MS : Units.WIND_KMH,
                            precipitation.getCheckedRadioButtonId() == 301 + 1 ? Units.PRECIP_IN : Units.PRECIP_MM);
                    AppPrefs.setUnits(this, next);
                    units = next;
                    renderPage();
                    WidgetUpdater.onPlaceOrDataChanged(this);
                }).show();
    }

    private RadioGroup unitGroup(LinearLayout parent, String title, String[] options, int baseId, int checked) {
        parent.addView(label(title, 13, palette.muted, true), margins(-1, -2, 0, 8, 0, 2));
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < options.length; i++) {
            RadioButton button = new RadioButton(this);
            button.setId(baseId + i + 1);
            button.setText(options[i]);
            button.setTextColor(palette.ink);
            button.setMinimumHeight(dp(48));
            group.addView(button, new RadioGroup.LayoutParams(0, -2, 1));
            if (i == checked) button.setChecked(true);
        }
        parent.addView(group, matchWrap());
        return group;
    }

    private void exportBackup() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "atemporal-backup-" + new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .format(new java.util.Date()) + ".json");
        try {
            startActivityForResult(intent, REQUEST_EXPORT);
        } catch (ActivityNotFoundException missing) {
            Toast.makeText(this, "Nenhum app para salvar arquivos foi encontrado.", Toast.LENGTH_LONG).show();
        }
    }

    private void importBackup() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
        try {
            startActivityForResult(intent, REQUEST_IMPORT);
        } catch (ActivityNotFoundException missing) {
            Toast.makeText(this, "Nenhum app para abrir arquivos foi encontrado.", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQUEST_EXPORT) writeBackup(uri);
        else if (requestCode == REQUEST_IMPORT) readBackup(uri);
    }

    private void writeBackup(Uri uri) {
        background(() -> {
            String message;
            try {
                byte[] bytes = AppPrefs.exportBackup(this).toString(2).getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = openForWrite(uri)) {
                    out.write(bytes);
                }
                message = "Backup salvo com " + AppPrefs.favorites(this).size() + " favoritas.";
            } catch (JSONException | IOException | SecurityException failed) {
                message = "Não foi possível salvar o backup.";
            }
            final String text = message;
            main.post(() -> {
                if (!isFinishing() && !isDestroyed()) Toast.makeText(this, text, Toast.LENGTH_LONG).show();
            });
        });
    }

    private OutputStream openForWrite(Uri uri) throws IOException {
        try {
            OutputStream out = getContentResolver().openOutputStream(uri, "wt");
            if (out != null) return out;
        } catch (IOException | IllegalArgumentException ignored) {
            // Alguns provedores não aceitam "wt": tenta "w" abaixo.
        }
        OutputStream out = getContentResolver().openOutputStream(uri, "w");
        if (out == null) throw new IOException("Destino indisponível.");
        return out;
    }

    private void readBackup(Uri uri) {
        background(() -> {
            try {
                JSONObject backup = new JSONObject(readText(uri));
                int added = AppPrefs.importBackup(this, backup);
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    reloadFromPrefs();
                    renderPage();
                    refreshWeather(false);
                    WidgetUpdater.onPlaceOrDataChanged(this);
                    showMessage("Backup importado", "Backup importado: " + added + " cidades novas.");
                });
            } catch (IllegalArgumentException notBackup) {
                final String text = notBackup.getMessage();
                main.post(() -> { if (!isFinishing() && !isDestroyed()) showMessage("Importar backup", text); });
            } catch (JSONException | IOException unreadable) {
                main.post(() -> {
                    if (!isFinishing() && !isDestroyed()) showMessage("Importar backup", "Não foi possível ler este arquivo.");
                });
            }
        });
    }

    private String readText(Uri uri) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Arquivo indisponível.");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long total = 0;
            int count;
            while ((count = in.read(buffer)) != -1) {
                total += count;
                if (total > MAX_BACKUP_BYTES) throw new IOException("Arquivo grande demais.");
                out.write(buffer, 0, count);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void reloadFromPrefs() {
        favorites.clear();
        favorites.addAll(AppPrefs.favorites(this));
        units = AppPrefs.units(this);
    }

    private void showMessage(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Entendi", null)
                .show();
    }

    private void pinWeatherWidget() {
        if (activePlace == null) {
            Toast.makeText(this, "Escolha uma cidade antes de adicionar o widget.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (AppPrefs.widgetPlace(this) == null) AppPrefs.setWidgetPlace(this, activePlace);
        WidgetUpdater.onPlaceOrDataChanged(this);
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
        Toast.makeText(this, "Na tela inicial, abra Widgets e arraste o Atemporal.", Toast.LENGTH_LONG).show();
    }

    private List<WeatherSnapshot.Hourly> visibleHours(WeatherSnapshot snapshot) {
        String today = snapshot.localToday();
        String date = tomorrowTab ? ForecastTime.addDays(today, 1) : today;
        return hoursFor(snapshot, date);
    }

    private List<WeatherSnapshot.Hourly> hoursFor(WeatherSnapshot snapshot, String date) {
        List<WeatherSnapshot.Hourly> result = new ArrayList<>();
        for (int index : ForecastTime.forDate(snapshot.hourlyTimes(), date, snapshot.localNow())) {
            result.add(snapshot.hourly.get(index));
        }
        return result;
    }

    private List<WeatherSnapshot.Hourly> hoursForDate(WeatherSnapshot snapshot, String date) {
        return hoursFor(snapshot, date);
    }

    private WeatherSnapshot.Hourly nextHour(WeatherSnapshot snapshot) {
        List<Integer> upcoming = ForecastTime.remainingToday(snapshot.hourlyTimes(), snapshot.localNow(), false);
        return upcoming.isEmpty() ? null : snapshot.hourly.get(upcoming.get(0));
    }

    private WeatherSnapshot.Daily todayDaily(WeatherSnapshot snapshot) {
        List<WeatherSnapshot.Daily> days = snapshot.upcomingDaily();
        if (days.isEmpty() || !days.get(0).date.equals(snapshot.localToday())) return null;
        return days.get(0);
    }

    private String windText(double kmh, double degrees) {
        String compass = Format.compass(degrees);
        return Format.wind(kmh, units) + (compass.isEmpty() ? "" : " " + compass);
    }

    private LinearLayout metric(String title, String value, String detail) {
        LinearLayout box = verticalCard(palette.surfaceAlt);
        box.setPadding(dp(10), dp(9), dp(10), dp(8));
        box.addView(label(title.toUpperCase(new Locale("pt", "BR")), 10, palette.muted, true), wrapWrap());
        box.addView(label(value, 17, palette.ink, true), margins(-1, -2, 0, 3, 0, 0));
        box.addView(label(detail, 10, palette.muted, false), wrapWrap());
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
        column.addView(label(title, 16, palette.ink, true), wrapWrap());
        column.addView(label(subtitle, 11, palette.muted, false), margins(-1, -2, 0, 1, 0, 0));
        return column;
    }

    private TextView tabLabel(String title, boolean active) {
        TextView view = label(title, 14, active ? palette.accent : palette.muted, true);
        view.setGravity(Gravity.CENTER);
        if (active) view.setBackground(rounded(palette.surface, 11));
        view.setSelected(active);
        view.setContentDescription(active ? "Aba " + title + ", selecionada" : "Aba " + title);
        return view;
    }

    private View infoCard(String message) {
        LinearLayout card = verticalCard(palette.surface);
        card.addView(label(message, 13, palette.muted, false), matchWrap());
        return card;
    }

    private HorizontalScrollView chartScroller(View chart, int width, int height) {
        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setHorizontalScrollBarEnabled(false);
        horizontal.setFillViewport(false);
        horizontal.setBackground(rounded(palette.surface, 18));
        horizontal.addView(chart, new HorizontalScrollView.LayoutParams(width, height));
        return horizontal;
    }

    private Button button(String title, boolean primary) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setTextColor(primary ? Color.WHITE : palette.ink);
        button.setBackground(rounded(primary ? palette.accent : palette.paleAccent, 13));
        return button;
    }

    private TextView actionText(String title, String description, View.OnClickListener action) {
        TextView button = label(title, 14, palette.accent, true);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(description);
        button.setBackground(rounded(palette.surface, 14));
        button.setOnClickListener(action);
        return button;
    }

    private TextView actionButton(String title, String description, View.OnClickListener action) {
        TextView button = label(title, 22, palette.accent, true);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(description);
        button.setBackground(rounded(palette.surface, 14));
        button.setOnClickListener(action);
        return button;
    }

    private ImageView weatherImage(int code, boolean day) {
        ImageView image = new ImageView(this);
        image.setImageResource(WeatherIcons.res(code, day));
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return image;
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
        } catch (RuntimeException ignored) {
            // Sem tela de configurações disponível: o cartão de status continua oferecendo a busca.
        }
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
