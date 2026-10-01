package com.tempoagora;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Scrollable temperature and precipitation chart drawn with the Android Canvas API. */
public final class WeatherChartView extends View {
    public interface SelectionListener { void onSelected(int index); }

    private static final int HOURLY_MODE = 1;
    private static final int DAILY_MODE = 2;
    private static final int INK = 0xFF183544;
    private static final int MUTED = 0xFF72838C;
    private static final int GRID = 0xFFE6EDF0;
    private static final int TEMP = 0xFFE07838;
    private static final int RAIN = 0xFF339DC5;
    private static final int CHANCE = 0xFF7556B5;
    private static final int SELECTED = 0xFF177FAE;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path chancePath = new Path();
    private final RectF rect = new RectF();
    private final float density;
    private final float textScale;
    private int mode = HOURLY_MODE;
    private List<WeatherSnapshot.Hourly> hours = new ArrayList<>();
    private List<WeatherSnapshot.Daily> days = new ArrayList<>();
    private int selected = -1;
    private SelectionListener listener;

    public WeatherChartView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        textScale = getResources().getDisplayMetrics().scaledDensity;
        setClickable(true);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    public void setHourly(List<WeatherSnapshot.Hourly> values, int selectedIndex) {
        mode = HOURLY_MODE;
        hours = values == null ? new ArrayList<WeatherSnapshot.Hourly>() : values;
        selected = selectedIndex;
        days = new ArrayList<>();
        invalidate();
    }

    public void setDaily(List<WeatherSnapshot.Daily> values, int selectedIndex) {
        mode = DAILY_MODE;
        days = values == null ? new ArrayList<WeatherSnapshot.Daily>() : values;
        selected = selectedIndex;
        hours = new ArrayList<>();
        invalidate();
    }

    public void setSelectionListener(SelectionListener value) { listener = value; }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        if (MeasureSpec.getMode(widthSpec) != MeasureSpec.EXACTLY) width = dp(320);
        int desiredHeight = dp(mode == HOURLY_MODE ? 238 : 210);
        int height = MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY
                ? MeasureSpec.getSize(heightSpec) : desiredHeight;
        setMeasuredDimension(width, height);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int count = mode == HOURLY_MODE ? hours.size() : days.size();
        if (count == 0) {
            drawText(canvas, "Sem dados para desenhar", dp(20), getHeight() / 2f, MUTED, 12);
            return;
        }
        float left = dp(40);
        float right = getWidth() - dp(12);
        float top = dp(30);
        float bottom = getHeight() - dp(38);
        if (right <= left || bottom <= top) return;
        drawLegend(canvas, left, dp(18));
        if (mode == HOURLY_MODE) drawHourly(canvas, count, left, right, top, bottom);
        else drawDaily(canvas, count, left, right, top, bottom);
    }

    private void drawHourly(Canvas canvas, int count, float left, float right, float top, float bottom) {
        float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY, rainMax = 0;
        for (WeatherSnapshot.Hourly hour : hours) {
            if (valid(hour.temperature)) { min = Math.min(min, (float) hour.temperature); max = Math.max(max, (float) hour.temperature); }
            if (valid(hour.precipitation)) rainMax = Math.max(rainMax, (float) hour.precipitation);
        }
        if (!Float.isFinite(min)) { min = 0; max = 1; }
        if (max - min < 3f) { float mid = (max + min) / 2f; min = mid - 2f; max = mid + 2f; }
        float span = max - min;
        float plotHeight = bottom - top;
        float tempTop = top + plotHeight * 0.05f;
        float tempBottom = top + plotHeight * 0.62f;
        float rainTop = top + plotHeight * 0.73f;
        float rainBottom = bottom - dp(12);
        float cell = (right - left) / count;

        paint.setColor(GRID); paint.setStrokeWidth(dp(1)); paint.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < 4; i++) {
            float y = tempTop + (tempBottom - tempTop) * i / 3f;
            canvas.drawLine(left, y, right, y, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        drawText(canvas, Math.round(max) + "°", dp(2), tempTop + dp(4), MUTED, 10);
        drawText(canvas, Math.round(min) + "°", dp(2), tempBottom, MUTED, 10);

        path.reset();
        chancePath.reset();
        boolean started = false;
        boolean chanceStarted = false;
        for (int i = 0; i < count; i++) {
            WeatherSnapshot.Hourly hour = hours.get(i);
            float x = left + cell * (i + 0.5f);
            if (valid(hour.precipitation) && rainMax > 0) {
                float height = (float) Math.max(dp(2), (hour.precipitation / Math.max(rainMax, 0.2)) * (rainBottom - rainTop));
                float barWidth = Math.min(dp(18), cell * 0.52f);
                rect.set(x - barWidth / 2, rainBottom - height, x + barWidth / 2, rainBottom);
                paint.setColor(i == selected ? SELECTED : RAIN); paint.setStyle(Paint.Style.FILL);
                canvas.drawRoundRect(rect, dp(4), dp(4), paint);
            }
            if (valid(hour.temperature)) {
                float y = tempBottom - (float) (hour.temperature - min) / span * (tempBottom - tempTop);
                if (!started) { path.moveTo(x, y); started = true; } else path.lineTo(x, y);
            }
            if (valid(hour.precipitationProbability)) {
                float y = rainBottom - (float) (hour.precipitationProbability / 100.0) * (rainBottom - rainTop);
                if (!chanceStarted) { chancePath.moveTo(x, y); chanceStarted = true; }
                else chancePath.lineTo(x, y);
            }
        }
        paint.setColor(TEMP); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2.6f));
        paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawPath(path, paint);
        paint.setColor(CHANCE); paint.setStrokeWidth(dp(1.8f));
        paint.setPathEffect(new DashPathEffect(new float[]{dp(4), dp(3)}, 0));
        canvas.drawPath(chancePath, paint);
        paint.setPathEffect(null);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < count; i++) {
            WeatherSnapshot.Hourly hour = hours.get(i);
            float x = left + cell * (i + 0.5f);
            if (valid(hour.temperature)) {
                float y = tempBottom - (float) (hour.temperature - min) / span * (tempBottom - tempTop);
                paint.setColor(i == selected ? SELECTED : TEMP);
                canvas.drawCircle(x, y, dp(i == selected ? 4 : 2.5f), paint);
                if (i % 3 == 0 || i == count - 1) {
                    String time = hour.time.length() >= 16 ? hour.time.substring(11, 16) : "";
                    drawCentered(canvas, time, x, getHeight() - dp(8), MUTED, 10);
                }
            }
            if (valid(hour.precipitationProbability)) {
                float chanceY = rainBottom - (float) (hour.precipitationProbability / 100.0) * (rainBottom - rainTop);
                paint.setColor(CHANCE);
                canvas.drawCircle(x, chanceY, dp(i == selected ? 4 : 2.6f), paint);
            }
        }
        drawText(canvas, "100%", right - dp(33), rainTop - dp(2), CHANCE, 9);
        drawText(canvas, "0%", right - dp(22), rainBottom, CHANCE, 9);
        drawSelection(canvas, count, left, right, top, bottom);
    }

    private void drawDaily(Canvas canvas, int count, float left, float right, float top, float bottom) {
        float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY, rainMax = 0;
        for (WeatherSnapshot.Daily day : days) {
            if (valid(day.minTemperature)) min = Math.min(min, (float) day.minTemperature);
            if (valid(day.maxTemperature)) max = Math.max(max, (float) day.maxTemperature);
            if (valid(day.precipitation)) rainMax = Math.max(rainMax, (float) day.precipitation);
        }
        if (!Float.isFinite(min)) { min = 0; max = 1; }
        if (max - min < 4f) { float mid = (max + min) / 2f; min = mid - 3f; max = mid + 3f; }
        float span = max - min;
        float plotHeight = bottom - top;
        float tempTop = top + plotHeight * 0.04f;
        float tempBottom = top + plotHeight * 0.65f;
        float rainTop = top + plotHeight * 0.74f;
        float rainBottom = bottom - dp(10);
        float cell = (right - left) / count;
        chancePath.reset();
        boolean chanceStarted = false;
        paint.setColor(GRID); paint.setStrokeWidth(dp(1)); paint.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < 4; i++) {
            float y = tempTop + (tempBottom - tempTop) * i / 3f;
            canvas.drawLine(left, y, right, y, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        drawText(canvas, Math.round(max) + "°", dp(2), tempTop + dp(4), MUTED, 10);
        drawText(canvas, Math.round(min) + "°", dp(2), tempBottom, MUTED, 10);
        float rangeWidth = Math.min(dp(22), cell * 0.42f);
        for (int i = 0; i < count; i++) {
            WeatherSnapshot.Daily day = days.get(i);
            float x = left + cell * (i + 0.5f);
            if (valid(day.minTemperature) && valid(day.maxTemperature)) {
                float yMax = tempBottom - (float) (day.maxTemperature - min) / span * (tempBottom - tempTop);
                float yMin = tempBottom - (float) (day.minTemperature - min) / span * (tempBottom - tempTop);
                rect.set(x - rangeWidth / 2, yMax, x + rangeWidth / 2, yMin);
                paint.setColor(i == selected ? SELECTED : TEMP);
                canvas.drawRoundRect(rect, dp(7), dp(7), paint);
                paint.setColor(INK);
                drawCentered(canvas, Math.round(day.maxTemperature) + "°", x, yMax - dp(3), INK, 8);
                drawCentered(canvas, Math.round(day.minTemperature) + "°", x, yMin + dp(11), INK, 8);
            }
            if (valid(day.precipitation) && rainMax > 0) {
                float height = (float) Math.max(dp(2), day.precipitation / rainMax * (rainBottom - rainTop));
                float barWidth = Math.min(dp(18), cell * 0.45f);
                rect.set(x - barWidth / 2, rainBottom - height, x + barWidth / 2, rainBottom);
                paint.setColor(RAIN); canvas.drawRoundRect(rect, dp(4), dp(4), paint);
            }
            if (valid(day.precipitationProbability)) {
                float chanceY = rainBottom - (float) (day.precipitationProbability / 100.0) * (rainBottom - rainTop);
                if (!chanceStarted) { chancePath.moveTo(x, chanceY); chanceStarted = true; }
                else chancePath.lineTo(x, chanceY);
            }
            if (i % 2 == 0 || i == count - 1) {
                String date = day.date.length() >= 10 ? day.date.substring(5).replace('-', '/') : day.date;
                drawCentered(canvas, date, x, getHeight() - dp(8), MUTED, 9);
            }
        }
        paint.setColor(CHANCE); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1.8f));
        paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setPathEffect(new DashPathEffect(new float[]{dp(4), dp(3)}, 0));
        canvas.drawPath(chancePath, paint);
        paint.setPathEffect(null);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < count; i++) {
            WeatherSnapshot.Daily day = days.get(i);
            if (!valid(day.precipitationProbability)) continue;
            float x = left + cell * (i + 0.5f);
            float chanceY = rainBottom - (float) (day.precipitationProbability / 100.0) * (rainBottom - rainTop);
            paint.setColor(CHANCE);
            canvas.drawCircle(x, chanceY, dp(i == selected ? 4 : 2.6f), paint);
        }
        drawText(canvas, "100%", right - dp(33), rainTop - dp(2), CHANCE, 9);
        drawText(canvas, "0%", right - dp(22), rainBottom, CHANCE, 9);
        drawSelection(canvas, count, left, right, top, bottom);
    }

    private void drawSelection(Canvas canvas, int count, float left, float right, float top, float bottom) {
        if (selected < 0 || selected >= count) return;
        float cell = (right - left) / count;
        float x = left + cell * (selected + 0.5f);
        paint.setColor(0x45177FAE); paint.setStrokeWidth(dp(1)); paint.setStyle(Paint.Style.STROKE);
        canvas.drawLine(x, top, x, bottom, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawLegend(Canvas canvas, float left, float baseline) {
        paint.setColor(TEMP); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2.5f));
        canvas.drawLine(left, baseline - dp(3), left + dp(12), baseline - dp(3), paint);
        paint.setStyle(Paint.Style.FILL);
        drawText(canvas, "°C", left + dp(17), baseline, INK, 10);
        paint.setColor(RAIN);
        rect.set(left + dp(43), baseline - dp(10), left + dp(51), baseline - dp(2));
        canvas.drawRoundRect(rect, dp(2), dp(2), paint);
        drawText(canvas, "mm", left + dp(56), baseline, INK, 10);
        paint.setColor(CHANCE);
        canvas.drawCircle(left + dp(91), baseline - dp(4), dp(3), paint);
        drawText(canvas, "chance %", left + dp(99), baseline, INK, 10);
    }

    @Override public boolean onTouchEvent(android.view.MotionEvent event) {
        if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
            int count = mode == HOURLY_MODE ? hours.size() : days.size();
            float left = dp(40), right = getWidth() - dp(12);
            if (count > 0 && event.getX() >= left && event.getX() <= right) {
                int index = Math.max(0, Math.min(count - 1, (int) ((event.getX() - left) / ((right - left) / count))));
                selected = index;
                invalidate();
                if (listener != null) listener.onSelected(index);
                performClick();
            }
            return true;
        }
        return event.getAction() == android.view.MotionEvent.ACTION_DOWN || super.onTouchEvent(event);
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    private void drawText(Canvas canvas, String text, float x, float y, int color, float size) {
        paint.setColor(color); paint.setStyle(Paint.Style.FILL); paint.setTextSize(size * textScale);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
        canvas.drawText(text, x, y, paint);
    }

    private void drawCentered(Canvas canvas, String text, float x, float y, int color, float size) {
        paint.setTextSize(size * textScale); paint.setTypeface(android.graphics.Typeface.create("sans-serif", 0));
        paint.setColor(color); paint.setStyle(Paint.Style.FILL);
        canvas.drawText(text, x - paint.measureText(text) / 2f, y, paint);
    }

    private boolean valid(double value) { return !Double.isNaN(value) && !Double.isInfinite(value); }
    private int dp(float value) { return (int) (value * density + 0.5f); }
}
