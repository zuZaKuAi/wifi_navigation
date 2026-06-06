package com.example.wifinavigation;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Typeface;
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
import android.widget.ImageButton;
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
    private List<WifiPositioning.ApInfo> apCoordinateList;
    private FloorMapView mapView;
    private TextView positionText;
    private TextView statusText;
    private TextView detailText;
    private TextView headingOffsetText;
    private EditText destinationInput;
    private Spinner travelModeSpinner;
    private Spinner floorSpinner;
    private boolean running = true;
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
        updateHeadingOffsetText();

        try {
            apCoordinateList = WifiPositioning.loadApCoordinateList(this);
            apCoordinates = WifiPositioning.loadApCoordinates(this);
        } catch (IOException e) {
            statusText.setText("AP 좌표 파일을 읽지 못했습니다.");
            detailText.setText(e.getMessage());
        }

        IntentFilter filter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(scanReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(scanReceiver, filter);
        }

        if (hasRequiredPermissions()) {
            startPdr();
            requestScan();
            handler.postDelayed(periodicScan, REFRESH_MS);
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
            startPdr();
            requestScan();
            handler.postDelayed(periodicScan, REFRESH_MS);
        } else {
            statusText.setText("Wi-Fi 스캔 권한이 필요합니다.");
        }
    }

    private boolean detailExpanded = false;
    private LinearLayout detailPanel;
    private Button startStopBtn;

    private void buildUi() {
        int pad = dp(12);

        // Root: FrameLayout so map fills the whole screen and UI floats on top
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xffeeeeee);

        // --- Map (fills entire screen) ---
        mapView = new FloorMapView(this);
        root.addView(mapView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // --- Top search bar card ---
        LinearLayout topCard = new LinearLayout(this);
        topCard.setOrientation(LinearLayout.HORIZONTAL);
        topCard.setGravity(Gravity.CENTER_VERTICAL);
        topCard.setPadding(dp(14), dp(10), dp(14), dp(10));
        topCard.setBackground(makeRoundCard(0xffffffff, dp(28)));
        topCard.setElevation(dp(4));

        destinationInput = new EditText(this);
        destinationInput.setSingleLine(true);
        destinationInput.setHint("목적지 검색 (예: 302)");
        destinationInput.setTextSize(15f);
        destinationInput.setBackground(null);

        travelModeSpinner = new Spinner(this);
        ArrayAdapter<String> modeAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new String[]{"계단", "엘리베이터"});
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        travelModeSpinner.setAdapter(modeAdapter);

        Button guide = makeRoundButton("길찾기", 0xff03C75A, 0xffffffff);
        guide.setOnClickListener(v -> {
            activeDestinationQuery = destinationInput.getText().toString();
            clearFloorTransition();
            updateNavigationRoute();
        });

        topCard.addView(destinationInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        topCard.addView(travelModeSpinner, new LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT));
        topCard.addView(guide, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        topParams.setMargins(dp(14), dp(48), dp(14), 0);
        topParams.gravity = Gravity.TOP;
        root.addView(topCard, topParams);

        // --- Right-side floating controls ---
        LinearLayout rightControls = new LinearLayout(this);
        rightControls.setOrientation(LinearLayout.VERTICAL);
        rightControls.setGravity(Gravity.CENTER_HORIZONTAL);

        // Floor spinner card
        LinearLayout floorCard = new LinearLayout(this);
        floorCard.setOrientation(LinearLayout.VERTICAL);
        floorCard.setGravity(Gravity.CENTER);
        floorCard.setPadding(dp(4), dp(4), dp(4), dp(4));
        floorCard.setBackground(makeRoundCard(0xffffffff, dp(12)));
        floorCard.setElevation(dp(3));

        floorSpinner = new Spinner(this);
        ArrayAdapter<String> floorAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new String[]{"auto", "3F", "4F", "5F"});
        floorAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        floorSpinner.setAdapter(floorAdapter);
        floorSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String value = (String) parent.getItemAtPosition(position);
                requestedFloor = "auto".equals(value) ? null : Integer.parseInt(value.replace("F", ""));
                requestScan();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        floorCard.addView(floorSpinner);

        // Action buttons card
        LinearLayout actionCard = new LinearLayout(this);
        actionCard.setOrientation(LinearLayout.VERTICAL);
        actionCard.setGravity(Gravity.CENTER);
        actionCard.setPadding(dp(6), dp(6), dp(6), dp(6));
        actionCard.setBackground(makeRoundCard(0xffffffff, dp(12)));
        actionCard.setElevation(dp(3));

        Button refresh = makeFabButton("↻");
        refresh.setOnClickListener(v -> requestScan());

        startStopBtn = makeFabButton("■");
        startStopBtn.setOnClickListener(v -> {
            running = !running;
            startStopBtn.setText(running ? "■" : "▶");
            if (running) {
                startPdr();
                requestScan();
                handler.postDelayed(periodicScan, REFRESH_MS);
            } else {
                if (pdrTracker != null) pdrTracker.stop();
                handler.removeCallbacks(periodicScan);
                statusText.setText("정지됨");
            }
        });

        Button rotateLeft = makeFabButton("◀");
        rotateLeft.setOnClickListener(v -> adjustHeadingOffset(-5.0));

        headingOffsetText = new TextView(this);
        headingOffsetText.setTextSize(11f);
        headingOffsetText.setGravity(Gravity.CENTER);
        headingOffsetText.setPadding(0, dp(2), 0, dp(2));

        Button rotateRight = makeFabButton("▶");
        rotateRight.setOnClickListener(v -> adjustHeadingOffset(5.0));

        actionCard.addView(refresh);
        actionCard.addView(startStopBtn);
        actionCard.addView(rotateLeft);
        actionCard.addView(headingOffsetText);
        actionCard.addView(rotateRight);

        rightControls.addView(floorCard, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        rightControls.addView(makeVerticalSpacer(dp(8)));
        rightControls.addView(actionCard, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams rightParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        rightParams.setMargins(0, dp(130), dp(12), 0);
        rightParams.gravity = Gravity.TOP | Gravity.END;
        root.addView(rightControls, rightParams);

        // --- Bottom info card ---
        LinearLayout bottomCard = new LinearLayout(this);
        bottomCard.setOrientation(LinearLayout.VERTICAL);
        bottomCard.setPadding(dp(16), dp(10), dp(16), dp(14));
        bottomCard.setBackground(makeRoundCard(0xffffffff, dp(20)));
        bottomCard.setElevation(dp(6));

        // Drag handle
        View handle = new View(this);
        handle.setBackgroundColor(0xffcccccc);
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(36), dp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = dp(10);
        bottomCard.addView(handle, handleParams);

        positionText = new TextView(this);
        positionText.setTextSize(15f);
        positionText.setTypeface(null, Typeface.BOLD);
        positionText.setText("위치: -");

        statusText = new TextView(this);
        statusText.setTextSize(13f);
        statusText.setTextColor(0xff666666);
        statusText.setText("대기 중");
        statusText.setPadding(0, dp(2), 0, dp(4));

        // Toggle detail button
        Button toggleDetail = new Button(this);
        toggleDetail.setText("상세 ▼");
        toggleDetail.setTextSize(12f);
        toggleDetail.setTextColor(0xff03C75A);
        toggleDetail.setBackground(null);
        toggleDetail.setPadding(0, dp(2), 0, 0);
        toggleDetail.setOnClickListener(v -> {
            detailExpanded = !detailExpanded;
            detailPanel.setVisibility(detailExpanded ? View.VISIBLE : View.GONE);
            toggleDetail.setText(detailExpanded ? "상세 ▲" : "상세 ▼");
        });

        detailPanel = new LinearLayout(this);
        detailPanel.setOrientation(LinearLayout.VERTICAL);
        detailPanel.setVisibility(View.GONE);

        detailText = new TextView(this);
        detailText.setTextSize(12f);
        detailText.setTextColor(0xff444444);
        detailText.setPadding(0, dp(6), 0, 0);
        ScrollView details = new ScrollView(this);
        details.addView(detailText);
        detailPanel.addView(details, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(150)));

        bottomCard.addView(positionText);
        bottomCard.addView(statusText);
        bottomCard.addView(toggleDetail);
        bottomCard.addView(detailPanel);

        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        bottomParams.setMargins(dp(12), 0, dp(12), dp(16));
        bottomParams.gravity = Gravity.BOTTOM;
        root.addView(bottomCard, bottomParams);

        setContentView(root);
        updateHeadingOffsetText();
    }

    private GradientDrawable makeRoundCard(int bgColor, int cornerRadius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(bgColor);
        d.setCornerRadius(cornerRadius);
        return d;
    }

    private Button makeRoundButton(String text, int bgColor, int textColor) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(13f);
        btn.setTextColor(textColor);
        btn.setPadding(dp(14), dp(6), dp(14), dp(6));
        GradientDrawable d = new GradientDrawable();
        d.setColor(bgColor);
        d.setCornerRadius(dp(20));
        btn.setBackground(d);
        return btn;
    }

    private Button makeFabButton(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(16f);
        btn.setTextColor(0xff333333);
        btn.setPadding(dp(4), dp(4), dp(4), dp(4));
        btn.setBackground(null);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(40), dp(40));
        p.gravity = Gravity.CENTER_HORIZONTAL;
        btn.setLayoutParams(p);
        return btn;
    }

    private View makeVerticalSpacer(int height) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, height));
        return v;
    }

    private void requestScan() {
        if (!hasRequiredPermissions()) {
            requestRequiredPermissions();
            return;
        }
        if (apCoordinates == null) {
            return;
        }
        if (wifiManager == null) {
            statusText.setText("Wi-Fi 매니저를 사용할 수 없습니다.");
            return;
        }
        if (!wifiManager.isWifiEnabled()) {
            statusText.setText("Wi-Fi가 꺼져 있습니다. 설정에서 Wi-Fi를 켜세요.");
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            return;
        }

        statusText.setText("Wi-Fi 스캔 중...");
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
            statusText.setText("Wi-Fi 스캔 권한이 필요합니다.");
            return;
        }

        WifiPositioning.PositionResult position = WifiPositioning.estimate(results, apCoordinates, requestedFloor);
        if (position.estimate == null || position.floor == null) {
            latestMatched = position.matched;
            if (floorTransitionActive) {
                showFloorTransitionPosition();
                detailText.setText("새 층 Wi-Fi를 기다리는 중입니다.");
                return;
            }
            positionText.setText("위치: -");
            statusText.setText("매칭된 AP가 없습니다.");
            detailText.setText("감지된 Wi-Fi: " + position.wifiCount + "개\nap_coordinates.csv의 BSSID와 현재 스캔 결과를 확인하세요.");
            mapView.showPosition(position.floor, null, position.matched);
            return;
        }

        latestMatched = position.matched;
        if (floorTransitionActive) {
            if (floorTransitionTargetFloor != null && position.floor == floorTransitionTargetFloor) {
                completeFloorTransition(position);
            } else {
                showFloorTransitionPosition();
                detailText.setText(buildDetails(position));
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

        positionText.setText(String.format(Locale.US, "위치: %d층 (%.2f, %.2f)", position.floor, currentPoint.x, currentPoint.y));
        statusText.setText("마지막 갱신: " + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()));
        detailText.setText(buildDetails(position));
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
        positionText.setText(String.format(Locale.US, "위치: %d층 (%.2f, %.2f)", state.floor, currentPoint.x, currentPoint.y));
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
            statusText.setText("Destination not found: " + activeDestinationQuery);
            mapView.showRoute(null);
            return;
        }

        NavigationGraph.Point target = navigationGraph.snapToCorridor(destination.floor, destination.x, destination.y);
        if (hasArrived(currentPoint, target)) {
            activeDestinationQuery = null;
            mapView.showRoute(null);
            statusText.setText("목적지에 도착했습니다.");
            return;
        }

        NavigationGraph.Route route;
        if (currentPoint.floor == target.floor) {
            route = navigationGraph.route(currentPoint.floor, currentPoint, target);
            if (route.points.isEmpty()) {
                statusText.setText("No corridor route to " + activeDestinationQuery);
                mapView.showRoute(null);
                return;
            }
            statusText.setText(String.format(Locale.US, "Route to %s on %dF", activeDestinationQuery, target.floor));
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
                statusText.setText("No reachable connector for selected mode on this floor");
                mapView.showRoute(null);
                return;
            }
            String mode = type == NavigationGraph.ConnectorType.ELEVATOR ? "elevator" : "stairs";
            statusText.setText(String.format(
                    Locale.US,
                    "Go to nearest %s, then move to %dF for %s",
                    mode,
                    target.floor,
                    activeDestinationQuery
            ));
        }
        mapView.showRoute(route);
    }

    private void startFloorTransition(
            NavigationGraph.Point connector,
            int targetFloor,
            NavigationGraph.ConnectorType type
    ) {
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

        WifiPositioning.Estimate estimate = new WifiPositioning.Estimate(
                connector.x,
                connector.y,
                "floor_transition_complete"
        );
        positionText.setText(String.format(Locale.US, "위치: %d층 (%.2f, %.2f)", connector.floor, connector.x, connector.y));
        detailText.setText(buildDetails(position));
        mapView.showPosition(connector.floor, estimate, position.matched);
        updateNavigationRoute();
    }

    private void showFloorTransitionPosition() {
        if (floorTransitionLockedPoint == null) {
            return;
        }
        currentPoint = floorTransitionLockedPoint;
        WifiPositioning.Estimate estimate = new WifiPositioning.Estimate(
                currentPoint.x,
                currentPoint.y,
                "floor_transition_locked"
        );
        positionText.setText(String.format(Locale.US, "위치: %d층 (%.2f, %.2f)", currentPoint.floor, currentPoint.x, currentPoint.y));
        mapView.showPosition(currentPoint.floor, estimate, latestMatched);
        mapView.showRoute(null);
        String mode = floorTransitionConnectorType == NavigationGraph.ConnectorType.ELEVATOR ? "elevator" : "stairs";
        if (floorTransitionTargetFloor == null) {
            statusText.setText("층 이동 중: " + mode);
        } else {
            statusText.setText(String.format(Locale.US, "층 이동 중: %s로 %d층까지 이동하세요.", mode, floorTransitionTargetFloor));
        }
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
        getPreferences(MODE_PRIVATE)
                .edit()
                .putFloat(PREF_HEADING_OFFSET, (float) offset)
                .apply();
        updateHeadingOffsetText();
    }

    private void updateHeadingOffsetText() {
        if (headingOffsetText == null || pdrTracker == null) {
            return;
        }
        headingOffsetText.setText(String.format(Locale.US, "%.0f°", pdrTracker.getHeadingOffsetDegrees()));
    }

    private String buildDetails(WifiPositioning.PositionResult position) {
        StringBuilder builder = new StringBuilder();
        builder.append("SSID: ").append(WifiPositioning.TARGET_SSID).append('\n');
        builder.append("감지된 Wi-Fi: ").append(position.wifiCount).append("개\n");
        builder.append("매칭 AP: ").append(position.matched.size()).append("개\n");
        builder.append("추정 방식: ").append(position.estimate.method).append('\n');
        builder.append(String.format(Locale.US, "좌표 단위: 1칸 = %.3fm\n\n", WifiPositioning.CELL_SIZE_M));
        builder.append("사용된 AP\n");
        for (WifiPositioning.MatchedAp item : position.matched) {
            builder.append(String.format(
                    Locale.US,
                    "%s  %d dBm  (%.0f, %.0f)\n",
                    item.ap.ap,
                    item.rssiDbm,
                    item.ap.x,
                    item.ap.y
            ));
        }
        return builder.toString();
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
