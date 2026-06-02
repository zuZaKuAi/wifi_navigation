package com.example.wifinavigation;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 1001;
    private static final long REFRESH_MS = 3000L;
    private static final String PREF_HEADING_OFFSET = "heading_offset_degrees";
    private static final double ARRIVAL_THRESHOLD_CELLS = 1.0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WifiManager wifiManager;
    private Map<String, WifiPositioning.ApInfo> apCoordinates;
    private List<WifiPositioning.ApInfo> apCoordinateList = new ArrayList<>();
    private FloorMapView mapView;
    private TextView statusBanner;
    private TextView currentFloorText;
    private TextView currentPositionText;
    private TextView routeTitleText;
    private TextView routeSubtitleText;
    private TextView routeMetricText;
    private TextView accuracyText;
    private TextView headingText;
    private TextView debugText;
    private EditText destinationInput;
    private Spinner travelModeSpinner;
    private Spinner floorSpinner;
    private LinearLayout bottomSheet;
    private boolean running = true;
    private boolean debugVisible;
    private Integer requestedFloor;
    private PdrTracker pdrTracker;
    private NavigationGraph navigationGraph;
    private NavigationGraph.Point currentPoint;
    private String activeDestinationQuery;
    private List<WifiPositioning.MatchedAp> latestMatched = new ArrayList<>();
    private WifiFingerprint lastWifiFingerprint;
    private boolean floorTransitionActive;
    private NavigationGraph.Point floorTransitionLockedPoint;
    private Integer floorTransitionTargetFloor;
    private NavigationGraph.ConnectorType floorTransitionConnectorType;

    private final Runnable periodicScan = new Runnable() {
        @Override
        public void run() {
            if (running) {
                requestScan();
                handler.postDelayed(this, REFRESH_MS);
            }
        }
    };

    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            showLatestScanResults();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        buildUi();
        navigationGraph = new NavigationGraph(this);
        pdrTracker = new PdrTracker(this, state -> runOnUiThread(() -> showPdrPosition(state)));
        pdrTracker.setHeadingOffsetDegrees(getPreferences(MODE_PRIVATE).getFloat(PREF_HEADING_OFFSET, -30f));
        updateHeadingText();

        try {
            apCoordinateList = WifiPositioning.loadApCoordinateList(this);
            apCoordinates = WifiPositioning.loadApCoordinates(this);
            showStatus("실내 지도를 준비했습니다.");
        } catch (IOException e) {
            showStatus("AP 좌표 파일을 읽지 못했습니다.");
            debugText.setText(e.getMessage());
        }

        IntentFilter filter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(scanReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(scanReceiver, filter);
        }

        if (hasRequiredPermissions()) {
            startTracking();
        } else {
            requestRequiredPermissions();
        }
    }

    @Override
    protected void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (pdrTracker != null) {
            pdrTracker.stop();
        }
        unregisterReceiver(scanReceiver);
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS && hasRequiredPermissions()) {
            startTracking();
        } else {
            showStatus("길안내를 위해 위치, Wi-Fi, 활동 권한이 필요합니다.");
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(238, 241, 245));

        mapView = new FloorMapView(this);
        root.addView(mapView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        LinearLayout topStack = new LinearLayout(this);
        topStack.setOrientation(LinearLayout.VERTICAL);
        topStack.setPadding(dp(14), dp(14), dp(14), 0);
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
        );
        root.addView(topStack, topParams);

        LinearLayout searchCard = panel();
        searchCard.setOrientation(LinearLayout.HORIZONTAL);
        searchCard.setGravity(Gravity.CENTER_VERTICAL);
        searchCard.setPadding(dp(14), dp(10), dp(10), dp(10));

        destinationInput = new EditText(this);
        destinationInput.setSingleLine(true);
        destinationInput.setHint("목적지 강의실 또는 호실");
        destinationInput.setTextSize(16f);
        destinationInput.setTextColor(Color.rgb(26, 32, 44));
        destinationInput.setHintTextColor(Color.rgb(117, 128, 145));
        destinationInput.setBackgroundColor(Color.TRANSPARENT);

        Button guideButton = pillButton("길찾기", Color.rgb(38, 99, 235), Color.WHITE);
        guideButton.setOnClickListener(v -> startGuidance());

        searchCard.addView(destinationInput, new LinearLayout.LayoutParams(0, dp(48), 1f));
        searchCard.addView(guideButton, new LinearLayout.LayoutParams(dp(88), dp(44)));
        topStack.addView(searchCard, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        statusBanner = new TextView(this);
        statusBanner.setTextSize(13f);
        statusBanner.setTextColor(Color.rgb(31, 41, 55));
        statusBanner.setPadding(dp(14), dp(9), dp(14), dp(9));
        statusBanner.setBackground(roundRect(Color.argb(235, 255, 255, 255), dp(18), Color.argb(30, 15, 23, 42), 1));
        LinearLayout.LayoutParams bannerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        bannerParams.topMargin = dp(10);
        topStack.addView(statusBanner, bannerParams);

        LinearLayout floatingControls = new LinearLayout(this);
        floatingControls.setOrientation(LinearLayout.VERTICAL);
        floatingControls.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams controlParams = new FrameLayout.LayoutParams(
                dp(58),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.RIGHT | Gravity.CENTER_VERTICAL
        );
        controlParams.rightMargin = dp(14);
        root.addView(floatingControls, controlParams);

        currentFloorText = floatingLabel("AUTO");
        Button locateButton = squareButton("◎");
        locateButton.setOnClickListener(v -> {
            mapView.resetViewport();
            requestScan();
        });
        Button pauseButton = squareButton("Ⅱ");
        pauseButton.setOnClickListener(v -> toggleTracking(pauseButton));
        Button debugButton = squareButton("⋯");
        debugButton.setOnClickListener(v -> toggleDebug());

        floorSpinner = new Spinner(this);
        ArrayAdapter<String> floorAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new String[]{"auto", "3F", "4F", "5F"});
        floorAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        floorSpinner.setAdapter(floorAdapter);
        floorSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String value = (String) parent.getItemAtPosition(position);
                requestedFloor = "auto".equals(value) ? null : Integer.parseInt(value.substring(0, 1));
                currentFloorText.setText(requestedFloor == null ? "AUTO" : value);
                requestScan();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        floatingControls.addView(currentFloorText, new LinearLayout.LayoutParams(dp(58), dp(34)));
        floatingControls.addView(locateButton, controlButtonParams());
        floatingControls.addView(pauseButton, controlButtonParams());
        floatingControls.addView(debugButton, controlButtonParams());
        floatingControls.addView(floorSpinner, new LinearLayout.LayoutParams(dp(58), dp(52)));

        bottomSheet = panel();
        bottomSheet.setOrientation(LinearLayout.VERTICAL);
        bottomSheet.setPadding(dp(18), dp(16), dp(18), dp(16));
        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
        );
        bottomParams.leftMargin = dp(12);
        bottomParams.rightMargin = dp(12);
        bottomParams.bottomMargin = dp(12);
        root.addView(bottomSheet, bottomParams);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout titleTexts = new LinearLayout(this);
        titleTexts.setOrientation(LinearLayout.VERTICAL);
        routeTitleText = label("현재 위치 확인 중", 19f, Color.rgb(17, 24, 39), true);
        routeSubtitleText = label("Wi-Fi 스캔 결과를 기다리고 있습니다.", 14f, Color.rgb(75, 85, 99), false);
        titleTexts.addView(routeTitleText);
        titleTexts.addView(routeSubtitleText);

        travelModeSpinner = new Spinner(this);
        ArrayAdapter<String> modeAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new String[]{"계단", "엘리베이터"});
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        travelModeSpinner.setAdapter(modeAdapter);
        travelModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateNavigationRoute();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        titleRow.addView(titleTexts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        titleRow.addView(travelModeSpinner, new LinearLayout.LayoutParams(dp(124), LinearLayout.LayoutParams.WRAP_CONTENT));
        bottomSheet.addView(titleRow);

        routeMetricText = label("거리 -- · 예상 --", 15f, Color.rgb(37, 99, 235), true);
        LinearLayout.LayoutParams metricParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        metricParams.topMargin = dp(10);
        bottomSheet.addView(routeMetricText, metricParams);

        currentPositionText = label("좌표 --", 13f, Color.rgb(75, 85, 99), false);
        accuracyText = label("AP 매칭 --", 13f, Color.rgb(75, 85, 99), false);
        headingText = label("방향 보정 -30°", 13f, Color.rgb(75, 85, 99), false);
        bottomSheet.addView(currentPositionText);
        bottomSheet.addView(accuracyText);
        bottomSheet.addView(headingText);

        LinearLayout calibrationRow = new LinearLayout(this);
        calibrationRow.setOrientation(LinearLayout.HORIZONTAL);
        Button left = pillButton("방향 -5°", Color.rgb(243, 244, 246), Color.rgb(31, 41, 55));
        Button right = pillButton("방향 +5°", Color.rgb(243, 244, 246), Color.rgb(31, 41, 55));
        left.setOnClickListener(v -> adjustHeadingOffset(-5.0));
        right.setOnClickListener(v -> adjustHeadingOffset(5.0));
        calibrationRow.addView(left, new LinearLayout.LayoutParams(0, dp(40), 1f));
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, dp(40), 1f);
        rightParams.leftMargin = dp(8);
        calibrationRow.addView(right, rightParams);
        LinearLayout.LayoutParams calibrationParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        calibrationParams.topMargin = dp(10);
        bottomSheet.addView(calibrationRow, calibrationParams);

        debugText = label("", 12f, Color.rgb(75, 85, 99), false);
        debugText.setVisibility(View.GONE);
        ScrollView debugScroll = new ScrollView(this);
        debugScroll.addView(debugText);
        LinearLayout.LayoutParams debugParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(130)
        );
        debugParams.topMargin = dp(8);
        bottomSheet.addView(debugScroll, debugParams);
        debugScroll.setVisibility(View.GONE);
        debugText.setTag(debugScroll);

        setContentView(root);
    }

    private void startTracking() {
        running = true;
        startPdr();
        requestScan();
        handler.removeCallbacks(periodicScan);
        handler.postDelayed(periodicScan, REFRESH_MS);
    }

    private void startGuidance() {
        activeDestinationQuery = destinationInput.getText().toString();
        clearFloorTransition();
        updateNavigationRoute();
    }

    private void toggleTracking(Button button) {
        running = !running;
        if (running) {
            button.setText("Ⅱ");
            startTracking();
            showStatus("실시간 위치 갱신을 다시 시작했습니다.");
        } else {
            button.setText("▶");
            if (pdrTracker != null) {
                pdrTracker.stop();
            }
            handler.removeCallbacks(periodicScan);
            showStatus("실시간 위치 갱신을 일시정지했습니다.");
        }
    }

    private void toggleDebug() {
        debugVisible = !debugVisible;
        View scroll = (View) debugText.getTag();
        scroll.setVisibility(debugVisible ? View.VISIBLE : View.GONE);
        debugText.setVisibility(debugVisible ? View.VISIBLE : View.GONE);
        mapView.setShowApDebug(debugVisible);
    }

    private void requestScan() {
        if (!hasRequiredPermissions()) {
            requestRequiredPermissions();
            return;
        }
        if (apCoordinates == null || wifiManager == null) {
            return;
        }
        if (!wifiManager.isWifiEnabled()) {
            showStatus("Wi-Fi가 꺼져 있습니다. 설정에서 Wi-Fi를 켜주세요.");
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            return;
        }

        showStatus("현재 위치를 갱신하는 중입니다.");
        boolean started = wifiManager.startScan();
        if (!started) {
            showLatestScanResults();
        }
    }

    private void showLatestScanResults() {
        if (!hasRequiredPermissions() || apCoordinates == null || wifiManager == null) {
            return;
        }

        List<ScanResult> results;
        try {
            results = wifiManager.getScanResults();
        } catch (SecurityException e) {
            showStatus("Wi-Fi 스캔 권한이 필요합니다.");
            return;
        }

        WifiPositioning.PositionResult position = WifiPositioning.estimate(results, apCoordinates, requestedFloor);
        if (position.estimate == null || position.floor == null) {
            latestMatched = position.matched;
            if (floorTransitionActive) {
                showFloorTransitionPosition();
                debugText.setText("목표 층 Wi-Fi를 기다리는 중입니다.");
                return;
            }
            routeTitleText.setText("위치를 찾지 못했습니다");
            routeSubtitleText.setText("주변 AP와 좌표 파일의 BSSID를 확인하세요.");
            routeMetricText.setText("거리 -- · 예상 --");
            currentPositionText.setText("좌표 --");
            accuracyText.setText("감지 Wi-Fi " + position.wifiCount + "개 · 매칭 AP 0개");
            showStatus("매칭되는 AP가 없습니다.");
            debugText.setText("감지 Wi-Fi: " + position.wifiCount + "개\nap_coordinates.csv와 현재 스캔 결과를 확인하세요.");
            mapView.showPosition(position.floor, null, position.matched);
            return;
        }

        latestMatched = position.matched;
        if (floorTransitionActive) {
            if (floorTransitionTargetFloor != null && position.floor == floorTransitionTargetFloor) {
                completeFloorTransition(position);
            } else {
                showFloorTransitionPosition();
                debugText.setText(buildDetails(position));
            }
            return;
        }

        WifiFingerprint currentFingerprint = WifiFingerprint.from(position.floor, position.matched);
        boolean wifiChanged = lastWifiFingerprint == null || lastWifiFingerprint.isDifferentFrom(currentFingerprint);
        lastWifiFingerprint = currentFingerprint;

        PdrTracker.State fused = null;
        boolean wifiCorrectionHeld = false;
        if (pdrTracker != null) {
            if (wifiChanged) {
                fused = pdrTracker.correctWithWifi(position.floor, position.estimate.x, position.estimate.y);
            } else {
                fused = pdrTracker.currentState();
                wifiCorrectionHeld = fused != null;
            }
        }
        double displayX = fused == null ? position.estimate.x : fused.x;
        double displayY = fused == null ? position.estimate.y : fused.y;
        currentPoint = navigationGraph.snapToCorridor(position.floor, displayX, displayY);
        WifiPositioning.Estimate displayEstimate = new WifiPositioning.Estimate(
                currentPoint.x,
                currentPoint.y,
                (fused == null ? position.estimate.method : (wifiCorrectionHeld ? "pdr_wifi_held" : "pdr_wifi_fused")) + "_corridor"
        );

        currentFloorText.setText(position.floor + "F");
        currentPositionText.setText(String.format(Locale.US, "현재 위치 %dF · %.2f, %.2f", position.floor, currentPoint.x, currentPoint.y));
        accuracyText.setText("감지 Wi-Fi " + position.wifiCount + "개 · 매칭 AP " + position.matched.size() + "개");
        routeTitleText.setText(activeDestinationQuery == null ? "현재 위치" : "경로 안내 중");
        routeSubtitleText.setText("마지막 갱신 " + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()));
        showStatus("실내 위치가 갱신되었습니다.");
        debugText.setText(buildDetails(position));
        mapView.showPosition(position.floor, displayEstimate, position.matched);
        updateNavigationRoute();
    }

    private void showPdrPosition(PdrTracker.State state) {
        if (state == null) {
            return;
        }
        if (floorTransitionActive) {
            showFloorTransitionPosition();
            return;
        }
        currentPoint = navigationGraph.snapToCorridor(state.floor, state.x, state.y);
        WifiPositioning.Estimate estimate = new WifiPositioning.Estimate(currentPoint.x, currentPoint.y, "pdr_step_corridor");
        currentFloorText.setText(state.floor + "F");
        currentPositionText.setText(String.format(Locale.US, "현재 위치 %dF · %.2f, %.2f", state.floor, currentPoint.x, currentPoint.y));
        mapView.showPosition(state.floor, estimate, latestMatched);
        updateNavigationRoute();
    }

    private void updateNavigationRoute() {
        if (activeDestinationQuery == null || activeDestinationQuery.trim().isEmpty()
                || currentPoint == null || navigationGraph == null || apCoordinateList == null) {
            return;
        }

        WifiPositioning.ApInfo destination = navigationGraph.findDestination(apCoordinateList, activeDestinationQuery);
        if (destination == null) {
            routeTitleText.setText("목적지를 찾지 못했습니다");
            routeSubtitleText.setText(activeDestinationQuery + " 검색 결과가 없습니다.");
            routeMetricText.setText("거리 -- · 예상 --");
            showStatus("목적지를 다시 입력해 주세요.");
            mapView.showRoute(null);
            return;
        }

        NavigationGraph.Point target = navigationGraph.snapToCorridor(destination.floor, destination.x, destination.y);
        if (hasArrived(currentPoint, target)) {
            activeDestinationQuery = null;
            mapView.showRoute(null);
            routeTitleText.setText("도착했습니다");
            routeSubtitleText.setText(destination.room + " 주변에 도착했습니다.");
            routeMetricText.setText("거리 0m · 예상 0분");
            showStatus("목적지에 도착했습니다.");
            return;
        }

        NavigationGraph.Route route;
        if (currentPoint.floor == target.floor) {
            route = navigationGraph.route(currentPoint.floor, currentPoint, target);
            if (route.points.isEmpty()) {
                routeTitleText.setText("경로를 찾지 못했습니다");
                routeSubtitleText.setText("복도 마스크에서 연결된 길이 없습니다.");
                routeMetricText.setText("거리 -- · 예상 --");
                mapView.showRoute(null);
                return;
            }
            routeTitleText.setText(destination.room + " 안내");
            routeSubtitleText.setText(target.floor + "층에서 목적지까지 이동하세요.");
        } else {
            NavigationGraph.ConnectorType type = travelModeSpinner.getSelectedItemPosition() == 1
                    ? NavigationGraph.ConnectorType.ELEVATOR
                    : NavigationGraph.ConnectorType.STAIRS;
            NavigationGraph.Point connector = navigationGraph.nearestConnector(currentPoint.floor, currentPoint, type);
            if (hasArrived(currentPoint, connector)) {
                startFloorTransition(connector, target.floor, type);
                return;
            }
            route = navigationGraph.routeToNearestConnector(currentPoint.floor, currentPoint, type);
            if (route.points.isEmpty()) {
                routeTitleText.setText("층 이동 경로 없음");
                routeSubtitleText.setText("선택한 이동 수단으로 연결 가능한 지점을 찾지 못했습니다.");
                routeMetricText.setText("거리 -- · 예상 --");
                mapView.showRoute(null);
                return;
            }
            String mode = type == NavigationGraph.ConnectorType.ELEVATOR ? "엘리베이터" : "계단";
            routeTitleText.setText(mode + "까지 안내");
            routeSubtitleText.setText(mode + "으로 " + target.floor + "층 이동 후 " + destination.room + "까지 안내합니다.");
        }

        routeMetricText.setText(buildRouteMetric(route));
        mapView.showRoute(route);
    }

    private void startFloorTransition(NavigationGraph.Point connector, int targetFloor, NavigationGraph.ConnectorType type) {
        floorTransitionActive = true;
        floorTransitionLockedPoint = connector;
        floorTransitionTargetFloor = targetFloor;
        floorTransitionConnectorType = type;
        showFloorTransitionPosition();
    }

    private void completeFloorTransition(WifiPositioning.PositionResult position) {
        NavigationGraph.Point reference = floorTransitionLockedPoint == null
                ? new NavigationGraph.Point(position.floor, position.estimate.x, position.estimate.y)
                : new NavigationGraph.Point(position.floor, floorTransitionLockedPoint.x, floorTransitionLockedPoint.y);
        NavigationGraph.Point connector = floorTransitionConnectorType == null
                ? null
                : navigationGraph.nearestConnector(position.floor, reference, floorTransitionConnectorType);
        if (connector == null) {
            connector = navigationGraph.snapToCorridor(position.floor, position.estimate.x, position.estimate.y);
        }
        if (pdrTracker != null) {
            pdrTracker.correctWithWifi(connector.floor, connector.x, connector.y);
        }
        currentPoint = connector;
        clearFloorTransition();

        WifiPositioning.Estimate estimate = new WifiPositioning.Estimate(connector.x, connector.y, "floor_transition_complete");
        currentFloorText.setText(connector.floor + "F");
        currentPositionText.setText(String.format(Locale.US, "현재 위치 %dF · %.2f, %.2f", connector.floor, connector.x, connector.y));
        debugText.setText(buildDetails(position));
        mapView.showPosition(connector.floor, estimate, position.matched);
        updateNavigationRoute();
    }

    private void showFloorTransitionPosition() {
        if (floorTransitionLockedPoint == null) {
            return;
        }
        currentPoint = floorTransitionLockedPoint;
        WifiPositioning.Estimate estimate = new WifiPositioning.Estimate(currentPoint.x, currentPoint.y, "floor_transition_locked");
        currentFloorText.setText(currentPoint.floor + "F");
        currentPositionText.setText(String.format(Locale.US, "현재 위치 %dF · %.2f, %.2f", currentPoint.floor, currentPoint.x, currentPoint.y));
        mapView.showPosition(currentPoint.floor, estimate, latestMatched);
        mapView.showRoute(null);
        String mode = floorTransitionConnectorType == NavigationGraph.ConnectorType.ELEVATOR ? "엘리베이터" : "계단";
        routeTitleText.setText("층 이동 중");
        routeSubtitleText.setText(mode + "으로 " + floorTransitionTargetFloor + "층까지 이동하세요.");
        routeMetricText.setText("목표 층 Wi-Fi 확인 대기 중");
        showStatus("층 이동 후 목표 층에서 위치를 다시 확인합니다.");
    }

    private void clearFloorTransition() {
        floorTransitionActive = false;
        floorTransitionLockedPoint = null;
        floorTransitionTargetFloor = null;
        floorTransitionConnectorType = null;
    }

    private boolean hasArrived(NavigationGraph.Point current, NavigationGraph.Point target) {
        if (current == null || target == null || current.floor != target.floor) {
            return false;
        }
        return Math.hypot(current.x - target.x, current.y - target.y) <= ARRIVAL_THRESHOLD_CELLS;
    }

    private void startPdr() {
        if (pdrTracker != null) {
            pdrTracker.start();
        }
    }

    private void adjustHeadingOffset(double deltaDegrees) {
        if (pdrTracker == null) {
            return;
        }
        double offset = pdrTracker.getHeadingOffsetDegrees() + deltaDegrees;
        pdrTracker.setHeadingOffsetDegrees(offset);
        getPreferences(MODE_PRIVATE).edit().putFloat(PREF_HEADING_OFFSET, (float) offset).apply();
        updateHeadingText();
    }

    private void updateHeadingText() {
        if (headingText == null || pdrTracker == null) {
            return;
        }
        headingText.setText(String.format(Locale.US, "방향 보정 %.0f°", pdrTracker.getHeadingOffsetDegrees()));
    }

    private String buildRouteMetric(NavigationGraph.Route route) {
        double cells = 0.0;
        for (int i = 1; i < route.points.size(); i++) {
            NavigationGraph.Point a = route.points.get(i - 1);
            NavigationGraph.Point b = route.points.get(i);
            cells += Math.hypot(a.x - b.x, a.y - b.y);
        }
        double meters = cells * WifiPositioning.CELL_SIZE_M;
        int minutes = Math.max(1, (int) Math.ceil(meters / 72.0));
        return String.format(Locale.US, "거리 %.0fm · 예상 %d분", meters, minutes);
    }

    private String buildDetails(WifiPositioning.PositionResult position) {
        StringBuilder builder = new StringBuilder();
        builder.append("SSID: ").append(WifiPositioning.TARGET_SSID).append('\n');
        builder.append("감지 Wi-Fi: ").append(position.wifiCount).append("개\n");
        builder.append("매칭 AP: ").append(position.matched.size()).append("개\n");
        builder.append("추정 방식: ").append(position.estimate.method).append('\n');
        builder.append(String.format(Locale.US, "좌표 단위: 1칸 = %.3fm\n\n", WifiPositioning.CELL_SIZE_M));
        for (WifiPositioning.MatchedAp item : position.matched) {
            builder.append(String.format(Locale.US, "%s  %d dBm  (%.0f, %.0f)\n", item.ap.ap, item.rssiDbm, item.ap.x, item.ap.y));
        }
        return builder.toString();
    }

    private void showStatus(String text) {
        if (statusBanner != null) {
            statusBanner.setText(text);
        }
    }

    private boolean hasRequiredPermissions() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestRequiredPermissions() {
        List<String> permissions = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACTIVITY_RECOGNITION);
        }
        requestPermissions(permissions.toArray(new String[0]), REQUEST_PERMISSIONS);
    }

    private LinearLayout panel() {
        LinearLayout layout = new LinearLayout(this);
        layout.setBackground(roundRect(Color.argb(242, 255, 255, 255), dp(22), Color.argb(28, 15, 23, 42), 1));
        layout.setElevation(dp(8));
        return layout;
    }

    private TextView label(String text, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(true);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return view;
    }

    private TextView floatingLabel(String text) {
        TextView view = label(text, 12f, Color.rgb(31, 41, 55), true);
        view.setGravity(Gravity.CENTER);
        view.setBackground(roundRect(Color.argb(244, 255, 255, 255), dp(16), Color.argb(30, 15, 23, 42), 1));
        view.setElevation(dp(5));
        return view;
    }

    private Button squareButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(17f);
        button.setTextColor(Color.rgb(31, 41, 55));
        button.setBackground(roundRect(Color.argb(244, 255, 255, 255), dp(18), Color.argb(30, 15, 23, 42), 1));
        button.setElevation(dp(5));
        return button;
    }

    private Button pillButton(String text, int background, int foreground) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(14f);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(foreground);
        button.setBackground(roundRect(background, dp(18), Color.TRANSPARENT, 0));
        return button;
    }

    private LinearLayout.LayoutParams controlButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(58), dp(52));
        params.topMargin = dp(8);
        return params;
    }

    private GradientDrawable roundRect(int color, int radius, int strokeColor, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) {
            drawable.setStroke(strokeWidth, strokeColor);
        }
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class WifiFingerprint {
        private static final int RSSI_CHANGE_THRESHOLD_DBM = 5;

        final int floor;
        final Map<String, Integer> rssiByBssid;

        private WifiFingerprint(int floor, Map<String, Integer> rssiByBssid) {
            this.floor = floor;
            this.rssiByBssid = rssiByBssid;
        }

        static WifiFingerprint from(int floor, List<WifiPositioning.MatchedAp> matched) {
            Map<String, Integer> rssiByBssid = new HashMap<>();
            for (WifiPositioning.MatchedAp item : matched) {
                rssiByBssid.put(item.ap.bssid, item.rssiDbm);
            }
            return new WifiFingerprint(floor, rssiByBssid);
        }

        boolean isDifferentFrom(WifiFingerprint other) {
            if (other == null || floor != other.floor || rssiByBssid.size() != other.rssiByBssid.size()) {
                return true;
            }
            for (Map.Entry<String, Integer> entry : rssiByBssid.entrySet()) {
                Integer otherRssi = other.rssiByBssid.get(entry.getKey());
                if (otherRssi == null || Math.abs(entry.getValue() - otherRssi) >= RSSI_CHANGE_THRESHOLD_DBM) {
                    return true;
                }
            }
            return false;
        }
    }
}
