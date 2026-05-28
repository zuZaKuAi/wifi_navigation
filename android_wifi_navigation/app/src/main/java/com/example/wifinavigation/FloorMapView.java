package com.example.wifinavigation;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.view.View;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class FloorMapView extends View {
    private static final Map<Integer, MapConfig> MAP_CONFIGS = createMapConfigs();

    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint apPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint apTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<Integer, Bitmap> maps = new HashMap<>();

    private Integer floor;
    private WifiPositioning.Estimate estimate;
    private List<WifiPositioning.MatchedAp> matched = Collections.emptyList();

    FloorMapView(Context context) {
        super(context);
        setBackgroundColor(Color.rgb(244, 245, 247));

        apPaint.setColor(Color.argb(190, 20, 94, 180));
        apTextPaint.setColor(Color.rgb(20, 94, 180));
        apTextPaint.setTextSize(26f);
        markerPaint.setColor(Color.rgb(226, 45, 75));
        markerStrokePaint.setStyle(Paint.Style.STROKE);
        markerStrokePaint.setStrokeWidth(8f);
        markerStrokePaint.setColor(Color.WHITE);
        labelPaint.setColor(Color.argb(235, 255, 255, 255));
        labelTextPaint.setColor(Color.rgb(35, 35, 35));
        labelTextPaint.setTextSize(28f);
    }

    void showPosition(Integer floor, WifiPositioning.Estimate estimate, List<WifiPositioning.MatchedAp> matched) {
        this.floor = floor;
        this.estimate = estimate;
        this.matched = matched == null ? Collections.emptyList() : matched;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (floor == null || !MAP_CONFIGS.containsKey(floor)) {
            drawCenteredText(canvas, "No floor selected");
            return;
        }

        Bitmap map = loadMap(floor);
        if (map == null) {
            drawCenteredText(canvas, "Map image not found");
            return;
        }

        RectF dst = fitCenter(map, getWidth(), getHeight());
        canvas.drawBitmap(map, null, dst, imagePaint);

        float scale = dst.width() / map.getWidth();
        for (WifiPositioning.MatchedAp item : matched) {
            PointF p = mapToView(item.ap.x, item.ap.y, floor, dst, scale);
            canvas.drawCircle(p.x, p.y, 9f, apPaint);
            canvas.drawText(item.ap.ap, p.x + 12f, p.y - 10f, apTextPaint);
        }

        if (estimate == null) {
            return;
        }

        PointF p = mapToView(estimate.x, estimate.y, floor, dst, scale);
        canvas.drawCircle(p.x, p.y, 24f, markerPaint);
        canvas.drawCircle(p.x, p.y, 24f, markerStrokePaint);
        canvas.drawCircle(p.x, p.y, 7f, labelPaint);

        String label = String.format(Locale.US, "현재 위치 (%.2f, %.2f)", estimate.x, estimate.y);
        float textWidth = labelTextPaint.measureText(label);
        float textHeight = labelTextPaint.getTextSize();
        float labelX = Math.min(Math.max(p.x + 28f, 12f), getWidth() - textWidth - 24f);
        float labelY = Math.min(Math.max(p.y - 46f, textHeight + 12f), getHeight() - 18f);
        RectF labelRect = new RectF(labelX - 12f, labelY - textHeight - 10f, labelX + textWidth + 12f, labelY + 10f);
        canvas.drawRoundRect(labelRect, 10f, 10f, labelPaint);
        canvas.drawText(label, labelX, labelY, labelTextPaint);
    }

    private Bitmap loadMap(int floor) {
        Bitmap cached = maps.get(floor);
        if (cached != null) {
            return cached;
        }
        try {
            Bitmap bitmap = BitmapFactory.decodeStream(getContext().getAssets().open(MAP_CONFIGS.get(floor).file));
            maps.put(floor, bitmap);
            return bitmap;
        } catch (IOException e) {
            return null;
        }
    }

    private static PointF mapToView(double x, double y, int floor, RectF dst, float viewScale) {
        MapConfig config = MAP_CONFIGS.get(floor);
        float px = (float) (config.originX + x * config.pixelPerUnit);
        float py = (float) (config.originY - y * config.pixelPerUnit);
        return new PointF(dst.left + px * viewScale, dst.top + py * viewScale);
    }

    private static RectF fitCenter(Bitmap bitmap, int viewWidth, int viewHeight) {
        float scale = Math.min((float) viewWidth / bitmap.getWidth(), (float) viewHeight / bitmap.getHeight());
        float width = bitmap.getWidth() * scale;
        float height = bitmap.getHeight() * scale;
        float left = (viewWidth - width) / 2f;
        float top = (viewHeight - height) / 2f;
        return new RectF(left, top, left + width, top + height);
    }

    private void drawCenteredText(Canvas canvas, String text) {
        labelTextPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(text, getWidth() / 2f, getHeight() / 2f, labelTextPaint);
        labelTextPaint.setTextAlign(Paint.Align.LEFT);
    }

    private static Map<Integer, MapConfig> createMapConfigs() {
        Map<Integer, MapConfig> configs = new HashMap<>();
        configs.put(3, new MapConfig("3fmap.png", 355f, 1083f, 31f));
        configs.put(4, new MapConfig("4fmap.png", 339f, 1064f, 31f));
        configs.put(5, new MapConfig("5fmap.png", 356f, 1129f, 31f));
        return configs;
    }

    private static final class MapConfig {
        final String file;
        final float originX;
        final float originY;
        final float pixelPerUnit;

        MapConfig(String file, float originX, float originY, float pixelPerUnit) {
            this.file = file;
            this.originX = originX;
            this.originY = originY;
            this.pixelPerUnit = pixelPerUnit;
        }
    }
}
