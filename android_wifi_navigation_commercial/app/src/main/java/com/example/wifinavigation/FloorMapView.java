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
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
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
    private static final float POSITION_FOCUS_ZOOM = 2.35f;
    private static final float MIN_USER_ZOOM = 0.65f;
    private static final float MAX_USER_ZOOM = 4.0f;
    private static final float VIRTUAL_VERTICAL_PADDING_RATIO = 0.55f;
    private static final float VIRTUAL_HORIZONTAL_PADDING_RATIO = 0.35f;

    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint virtualFieldPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint apPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint apTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wifiOnlyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headingConePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint routePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint routeHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint destinationPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<Integer, Bitmap> maps = new HashMap<>();
    private final ScaleGestureDetector scaleGestureDetector;

    private Integer floor;
    private WifiPositioning.Estimate estimate;
    private WifiPositioning.Estimate displayEstimate;
    private WifiPositioning.Estimate wifiOnlyEstimate;
    private Double headingRadians;
    private NavigationGraph.Route route;
    private List<WifiPositioning.MatchedAp> matched = Collections.emptyList();
    private ValueAnimator markerAnimator;
    private boolean showApDebug;
    private float userZoom = 1f;
    private float userPanX;
    private float userPanY;
    private float lastTouchX;
    private float lastTouchY;
    private boolean dragging;

    FloorMapView(Context context) {
        super(context);
        setBackgroundColor(Color.rgb(238, 241, 245));

        virtualFieldPaint.setColor(Color.WHITE);
        apPaint.setColor(Color.argb(190, 20, 94, 180));
        apTextPaint.setColor(Color.rgb(20, 94, 180));
        apTextPaint.setTextSize(26f);
        markerPaint.setColor(Color.rgb(226, 45, 75));
        wifiOnlyPaint.setColor(Color.rgb(37, 99, 235));
        headingConePaint.setColor(Color.argb(58, 226, 45, 75));
        headingConePaint.setStyle(Paint.Style.FILL);
        markerStrokePaint.setStyle(Paint.Style.STROKE);
        markerStrokePaint.setStrokeWidth(8f);
        markerStrokePaint.setColor(Color.WHITE);
        routeHaloPaint.setStyle(Paint.Style.STROKE);
        routeHaloPaint.setStrokeWidth(18f);
        routeHaloPaint.setStrokeCap(Paint.Cap.ROUND);
        routeHaloPaint.setStrokeJoin(Paint.Join.ROUND);
        routeHaloPaint.setColor(Color.argb(205, 255, 255, 255));
        routePaint.setStyle(Paint.Style.STROKE);
        routePaint.setStrokeWidth(10f);
        routePaint.setStrokeCap(Paint.Cap.ROUND);
        routePaint.setStrokeJoin(Paint.Join.ROUND);
        routePaint.setColor(Color.rgb(37, 99, 235));
        destinationPaint.setColor(Color.rgb(22, 163, 74));
        labelPaint.setColor(Color.argb(235, 255, 255, 255));
        labelTextPaint.setColor(Color.rgb(35, 35, 35));
        labelTextPaint.setTextSize(28f);

        scaleGestureDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float previousZoom = userZoom;
                userZoom = clamp(userZoom * detector.getScaleFactor(), MIN_USER_ZOOM, MAX_USER_ZOOM);
                float zoomRatio = userZoom / previousZoom;
                float focusX = detector.getFocusX();
                float focusY = detector.getFocusY();
                userPanX = focusX - (focusX - userPanX) * zoomRatio;
                userPanY = focusY - (focusY - userPanY) * zoomRatio;
                postInvalidateOnAnimation();
                return true;
            }
        });
    }

    void showPosition(Integer floor, WifiPositioning.Estimate estimate, List<WifiPositioning.MatchedAp> matched) {
        showPosition(floor, estimate, null, matched);
    }

    void showPosition(
            Integer floor,
            WifiPositioning.Estimate estimate,
            WifiPositioning.Estimate wifiOnlyEstimate,
            List<WifiPositioning.MatchedAp> matched
    ) {
        showPosition(floor, estimate, wifiOnlyEstimate, null, matched);
    }

    void showPosition(
            Integer floor,
            WifiPositioning.Estimate estimate,
            WifiPositioning.Estimate wifiOnlyEstimate,
            Double headingRadians,
            List<WifiPositioning.MatchedAp> matched
    ) {
        boolean floorChanged = this.floor == null || floor == null || !this.floor.equals(floor);
        this.floor = floor;
        this.estimate = estimate;
        this.wifiOnlyEstimate = floorChanged ? wifiOnlyEstimate : (wifiOnlyEstimate == null ? this.wifiOnlyEstimate : wifiOnlyEstimate);
        this.headingRadians = headingRadians;
        this.matched = matched == null ? Collections.emptyList() : matched;
        updateDisplayEstimate(floorChanged);
    }

    void showRoute(NavigationGraph.Route route) {
        this.route = route;
        postInvalidateOnAnimation();
    }

    void setShowApDebug(boolean showApDebug) {
        this.showApDebug = showApDebug;
        postInvalidateOnAnimation();
    }

    void resetViewport() {
        userZoom = 1f;
        userPanX = 0f;
        userPanY = 0f;
        postInvalidateOnAnimation();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleGestureDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                dragging = true;
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!scaleGestureDetector.isInProgress() && event.getPointerCount() == 1 && dragging) {
                    float x = event.getX();
                    float y = event.getY();
                    userPanX += x - lastTouchX;
                    userPanY += y - lastTouchY;
                    lastTouchX = x;
                    lastTouchY = y;
                    postInvalidateOnAnimation();
                }
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerCount() <= 2) {
                    int nextIndex = event.getActionIndex() == 0 ? 1 : 0;
                    if (nextIndex < event.getPointerCount()) {
                        lastTouchX = event.getX(nextIndex);
                        lastTouchY = event.getY(nextIndex);
                    }
                    dragging = true;
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                return true;
            default:
                return true;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (floor == null || !FloorConfig.CONFIGS.containsKey(floor)) {
            drawCenteredText(canvas, "층을 선택하거나 현재 위치를 갱신하세요");
            return;
        }

        Bitmap map = loadMap(floor);
        if (map == null) {
            drawCenteredText(canvas, "지도 이미지를 찾지 못했습니다");
            return;
        }

        WifiPositioning.Estimate markerEstimate = displayEstimate == null ? estimate : displayEstimate;
        RectF baseDst = markerEstimate == null
                ? fitCenter(map, getWidth(), getHeight())
                : focusAroundPosition(map, markerEstimate, floor, getWidth(), getHeight());
        RectF dst = applyUserViewport(baseDst, getWidth(), getHeight());
        drawVirtualField(canvas, dst);
        canvas.drawBitmap(map, null, dst, imagePaint);

        float scale = dst.width() / map.getWidth();
        drawRoute(canvas, dst, scale, routeHaloPaint);
        drawRoute(canvas, dst, scale, routePaint);

        if (showApDebug) {
            for (WifiPositioning.MatchedAp item : matched) {
                PointF p = mapToView(item.ap.x, item.ap.y, floor, dst, scale);
                canvas.drawCircle(p.x, p.y, 9f, apPaint);
                canvas.drawText(item.ap.ap, p.x + 12f, p.y - 10f, apTextPaint);
            }
        }

        if (markerEstimate == null) {
            return;
        }

        if (showApDebug && wifiOnlyEstimate != null) {
            PointF wifiPoint = mapToView(wifiOnlyEstimate.x, wifiOnlyEstimate.y, floor, dst, scale);
            canvas.drawCircle(wifiPoint.x, wifiPoint.y, 15f, wifiOnlyPaint);
            canvas.drawCircle(wifiPoint.x, wifiPoint.y, 15f, markerStrokePaint);
            canvas.drawCircle(wifiPoint.x, wifiPoint.y, 5f, labelPaint);
        }

        PointF p = mapToView(markerEstimate.x, markerEstimate.y, floor, dst, scale);
        drawHeadingCone(canvas, p);
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

    private void drawHeadingCone(Canvas canvas, PointF center) {
        if (headingRadians == null) {
            return;
        }
        float radius = 104f;
        float sweep = 58f;
        float centerAngle = (float) Math.toDegrees(Math.atan2(Math.cos(headingRadians), Math.sin(headingRadians)));
        RectF oval = new RectF(center.x - radius, center.y - radius, center.x + radius, center.y + radius);
        canvas.drawArc(oval, centerAngle - sweep / 2f, sweep, true, headingConePaint);
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

    private void drawRoute(Canvas canvas, RectF dst, float scale, Paint paint) {
        if (route == null || floor == null || route.floor != floor || route.points.size() < 2) {
            return;
        }
        NavigationGraph.Point previous = route.points.get(0);
        for (int i = 1; i < route.points.size(); i++) {
            NavigationGraph.Point current = route.points.get(i);
            PointF a = mapToView(previous.x, previous.y, floor, dst, scale);
            PointF b = mapToView(current.x, current.y, floor, dst, scale);
            canvas.drawLine(a.x, a.y, b.x, b.y, paint);
            previous = current;
        }
        if (paint == routePaint) {
            NavigationGraph.Point destination = route.points.get(route.points.size() - 1);
            PointF p = mapToView(destination.x, destination.y, floor, dst, scale);
            canvas.drawCircle(p.x, p.y, 17f, destinationPaint);
            canvas.drawCircle(p.x, p.y, 17f, markerStrokePaint);
        }
    }

    private static RectF fitCenter(Bitmap bitmap, int viewWidth, int viewHeight) {
        float scale = Math.min((float) viewWidth / bitmap.getWidth(), (float) viewHeight / bitmap.getHeight());
        float width = bitmap.getWidth() * scale;
        float height = bitmap.getHeight() * scale;
        float left = (viewWidth - width) / 2f;
        float top = (viewHeight - height) / 2f;
        return new RectF(left, top, left + width, top + height);
    }

    private static RectF focusAroundPosition(
            Bitmap bitmap,
            WifiPositioning.Estimate markerEstimate,
            int floor,
            int viewWidth,
            int viewHeight
    ) {
        RectF fit = fitCenter(bitmap, viewWidth, viewHeight);
        float fitScale = fit.width() / bitmap.getWidth();
        float scale = fitScale * POSITION_FOCUS_ZOOM;
        float width = bitmap.getWidth() * scale;
        float height = bitmap.getHeight() * scale;

        FloorConfig config = FloorConfig.CONFIGS.get(floor);
        float markerPx = config.unitToPixelX(markerEstimate.x);
        float markerPy = config.unitToPixelY(markerEstimate.y);
        float left = viewWidth / 2f - markerPx * scale;
        float top = viewHeight / 2f - markerPy * scale;

        if (width <= viewWidth) {
            left = (viewWidth - width) / 2f;
        } else {
            left = Math.min(0f, Math.max(viewWidth - width, left));
        }
        if (height <= viewHeight) {
            top = (viewHeight - height) / 2f;
        } else {
            top = Math.min(0f, Math.max(viewHeight - height, top));
        }
        return new RectF(left, top, left + width, top + height);
    }

    private RectF applyUserViewport(RectF base, int viewWidth, int viewHeight) {
        if (userZoom == 1f && userPanX == 0f && userPanY == 0f) {
            return base;
        }
        float centerX = viewWidth / 2f;
        float centerY = viewHeight / 2f;
        float left = centerX + (base.left - centerX) * userZoom + userPanX;
        float top = centerY + (base.top - centerY) * userZoom + userPanY;
        float width = base.width() * userZoom;
        float height = base.height() * userZoom;
        return clampToViewport(new RectF(left, top, left + width, top + height), viewWidth, viewHeight);
    }

    private static RectF clampToViewport(RectF rect, int viewWidth, int viewHeight) {
        float left = rect.left;
        float top = rect.top;
        float horizontalPadding = viewWidth * VIRTUAL_HORIZONTAL_PADDING_RATIO;
        float virtualWidth = rect.width() + horizontalPadding * 2f;
        if (virtualWidth <= viewWidth) {
            left = (viewWidth - rect.width()) / 2f;
        } else {
            float minLeft = viewWidth - rect.width() - horizontalPadding;
            float maxLeft = horizontalPadding;
            left = Math.min(maxLeft, Math.max(minLeft, left));
        }
        float verticalPadding = viewHeight * VIRTUAL_VERTICAL_PADDING_RATIO;
        float virtualHeight = rect.height() + verticalPadding * 2f;
        if (virtualHeight <= viewHeight) {
            top = (viewHeight - rect.height()) / 2f;
        } else {
            float minTop = viewHeight - rect.height() - verticalPadding;
            float maxTop = verticalPadding;
            top = Math.min(maxTop, Math.max(minTop, top));
        }
        return new RectF(left, top, left + rect.width(), top + rect.height());
    }

    private void drawVirtualField(Canvas canvas, RectF mapRect) {
        float horizontalPadding = getWidth() * VIRTUAL_HORIZONTAL_PADDING_RATIO;
        float verticalPadding = getHeight() * VIRTUAL_VERTICAL_PADDING_RATIO;
        RectF field = new RectF(
                mapRect.left - horizontalPadding,
                mapRect.top - verticalPadding,
                mapRect.right + horizontalPadding,
                mapRect.bottom + verticalPadding
        );
        canvas.drawRect(field, virtualFieldPaint);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void drawCenteredText(Canvas canvas, String text) {
        labelTextPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(text, getWidth() / 2f, getHeight() / 2f, labelTextPaint);
        labelTextPaint.setTextAlign(Paint.Align.LEFT);
    }
}
