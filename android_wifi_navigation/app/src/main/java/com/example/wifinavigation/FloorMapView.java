package com.example.wifinavigation;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class FloorMapView extends View {
    private static final long MARKER_ANIMATION_MS = 350L;

    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint apPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint apTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint routePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint destinationPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<Integer, Bitmap> maps = new HashMap<>();

    private Integer floor;
    private WifiPositioning.Estimate estimate;
    private WifiPositioning.Estimate displayEstimate;
    private NavigationGraph.Route route;
    private List<WifiPositioning.MatchedAp> matched = Collections.emptyList();
    private ValueAnimator markerAnimator;

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
        routePaint.setStyle(Paint.Style.STROKE);
        routePaint.setStrokeWidth(10f);
        routePaint.setStrokeCap(Paint.Cap.ROUND);
        routePaint.setStrokeJoin(Paint.Join.ROUND);
        routePaint.setColor(Color.rgb(37, 99, 235));
        destinationPaint.setColor(Color.rgb(22, 163, 74));
        labelPaint.setColor(Color.argb(235, 255, 255, 255));
        labelTextPaint.setColor(Color.rgb(35, 35, 35));
        labelTextPaint.setTextSize(28f);
    }

    void showPosition(Integer floor, WifiPositioning.Estimate estimate, List<WifiPositioning.MatchedAp> matched) {
        boolean floorChanged = this.floor == null || floor == null || !this.floor.equals(floor);
        this.floor = floor;
        this.estimate = estimate;
        this.matched = matched == null ? Collections.emptyList() : matched;
        updateDisplayEstimate(floorChanged);
    }

    void showRoute(NavigationGraph.Route route) {
        this.route = route;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (floor == null || !FloorConfig.CONFIGS.containsKey(floor)) {
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
        drawRoute(canvas, dst, scale);
        for (WifiPositioning.MatchedAp item : matched) {
            PointF p = mapToView(item.ap.x, item.ap.y, floor, dst, scale);
            canvas.drawCircle(p.x, p.y, 9f, apPaint);
            canvas.drawText(item.ap.ap, p.x + 12f, p.y - 10f, apTextPaint);
        }

        WifiPositioning.Estimate markerEstimate = displayEstimate == null ? estimate : displayEstimate;
        if (markerEstimate == null) {
            return;
        }

        PointF p = mapToView(markerEstimate.x, markerEstimate.y, floor, dst, scale);
        canvas.drawCircle(p.x, p.y, 24f, markerPaint);
        canvas.drawCircle(p.x, p.y, 24f, markerStrokePaint);
        canvas.drawCircle(p.x, p.y, 7f, labelPaint);

        String label = String.format(Locale.US, "현재 위치 (%.2f, %.2f)", markerEstimate.x, markerEstimate.y);
        float textWidth = labelTextPaint.measureText(label);
        float textHeight = labelTextPaint.getTextSize();
        float labelX = Math.min(Math.max(p.x + 28f, 12f), getWidth() - textWidth - 24f);
        float labelY = Math.min(Math.max(p.y - 46f, textHeight + 12f), getHeight() - 18f);
        RectF labelRect = new RectF(labelX - 12f, labelY - textHeight - 10f, labelX + textWidth + 12f, labelY + 10f);
        canvas.drawRoundRect(labelRect, 10f, 10f, labelPaint);
        canvas.drawText(label, labelX, labelY, labelTextPaint);
    }

    private void updateDisplayEstimate(boolean floorChanged) {
        if (markerAnimator != null) {
            markerAnimator.cancel();
            markerAnimator = null;
        }

        if (estimate == null || floorChanged || displayEstimate == null) {
            displayEstimate = estimate;
            postInvalidateOnAnimation();
            return;
        }

        double startX = displayEstimate.x;
        double startY = displayEstimate.y;
        double targetX = estimate.x;
        double targetY = estimate.y;
        double distance = Math.hypot(targetX - startX, targetY - startY);
        if (distance < 0.02) {
            displayEstimate = estimate;
            postInvalidateOnAnimation();
            return;
        }

        markerAnimator = ValueAnimator.ofFloat(0f, 1f);
        markerAnimator.setDuration(MARKER_ANIMATION_MS);
        markerAnimator.setInterpolator(new DecelerateInterpolator());
        markerAnimator.addUpdateListener(animation -> {
            float t = (float) animation.getAnimatedValue();
            double x = startX + (targetX - startX) * t;
            double y = startY + (targetY - startY) * t;
            displayEstimate = new WifiPositioning.Estimate(x, y, estimate.method);
            postInvalidateOnAnimation();
        });
        markerAnimator.start();
    }

    private Bitmap loadMap(int floor) {
        Bitmap cached = maps.get(floor);
        if (cached != null) {
            return cached;
        }
        try {
            Bitmap bitmap = BitmapFactory.decodeStream(getContext().getAssets().open(FloorConfig.CONFIGS.get(floor).mapFile));
            maps.put(floor, bitmap);
            return bitmap;
        } catch (IOException e) {
            return null;
        }
    }

    private static PointF mapToView(double x, double y, int floor, RectF dst, float viewScale) {
        FloorConfig config = FloorConfig.CONFIGS.get(floor);
        float px = config.unitToPixelX(x);
        float py = config.unitToPixelY(y);
        return new PointF(dst.left + px * viewScale, dst.top + py * viewScale);
    }

    private void drawRoute(Canvas canvas, RectF dst, float scale) {
        if (route == null || floor == null || route.floor != floor || route.points.size() < 2) {
            return;
        }
        NavigationGraph.Point previous = route.points.get(0);
        for (int i = 1; i < route.points.size(); i++) {
            NavigationGraph.Point current = route.points.get(i);
            PointF a = mapToView(previous.x, previous.y, floor, dst, scale);
            PointF b = mapToView(current.x, current.y, floor, dst, scale);
            canvas.drawLine(a.x, a.y, b.x, b.y, routePaint);
            previous = current;
        }
        NavigationGraph.Point destination = route.points.get(route.points.size() - 1);
        PointF p = mapToView(destination.x, destination.y, floor, dst, scale);
        canvas.drawCircle(p.x, p.y, 17f, destinationPaint);
        canvas.drawCircle(p.x, p.y, 17f, markerStrokePaint);
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

}
