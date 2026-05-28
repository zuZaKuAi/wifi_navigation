package com.example.wifinavigation;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 1001;
    private static final long REFRESH_MS = 3000L;
    private static final String PREF_HEADING_OFFSET = "heading_offset_degrees";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WifiManager wifiManager;
    private Map<String, WifiPositioning.ApInfo> apCoordinates;
    private FloorMapView mapView;
    private TextView positionText;
    private TextView statusText;
    private TextView detailText;
    private TextView headingOffsetText;
    private Spinner floorSpinner;
    private boolean running = true;
    private Integer requestedFloor;
    private PdrTracker pdrTracker;
    private List<WifiPositioning.MatchedAp> latestMatched = new ArrayList<>();

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
        pdrTracker = new PdrTracker(this, state -> runOnUiThread(() -> showPdrPosition(state)));
        pdrTracker.setHeadingOffsetDegrees(getPreferences(MODE_PRIVATE).getFloat(PREF_HEADING_OFFSET, -30f));
        updateHeadingOffsetText();

        try {
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

    private void buildUi() {
        int pad = dp(12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xfff5f6f8);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(pad, pad, pad, dp(8));

        floorSpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new String[]{"auto", "3", "4", "5"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        floorSpinner.setAdapter(adapter);
        floorSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String value = (String) parent.getItemAtPosition(position);
                requestedFloor = "auto".equals(value) ? null : Integer.parseInt(value);
                requestScan();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        Button refresh = new Button(this);
        refresh.setText("갱신");
        refresh.setOnClickListener(v -> requestScan());

        Button startStop = new Button(this);
        startStop.setText("정지");
        startStop.setOnClickListener(v -> {
            running = !running;
            startStop.setText(running ? "정지" : "시작");
            if (running) {
                startPdr();
                requestScan();
                handler.postDelayed(periodicScan, REFRESH_MS);
            } else {
                if (pdrTracker != null) {
                    pdrTracker.stop();
                }
                handler.removeCallbacks(periodicScan);
                statusText.setText("정지됨");
            }
        });

        Button rotateLeft = new Button(this);
        rotateLeft.setText("-5");
        rotateLeft.setOnClickListener(v -> adjustHeadingOffset(-5.0));

        Button rotateRight = new Button(this);
        rotateRight.setText("+5");
        rotateRight.setOnClickListener(v -> adjustHeadingOffset(5.0));

        headingOffsetText = new TextView(this);
        headingOffsetText.setTextSize(13f);
        headingOffsetText.setPadding(dp(8), 0, 0, 0);

        toolbar.addView(floorSpinner, new LinearLayout.LayoutParams(dp(110), LinearLayout.LayoutParams.WRAP_CONTENT));
        toolbar.addView(refresh, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        toolbar.addView(startStop, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        toolbar.addView(rotateLeft, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        toolbar.addView(rotateRight, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        toolbar.addView(headingOffsetText, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        updateHeadingOffsetText();

        positionText = new TextView(this);
        positionText.setText("위치: -");
        positionText.setTextSize(15f);
        positionText.setPadding(pad, 0, pad, dp(4));

        statusText = new TextView(this);
        statusText.setText("대기 중");
        statusText.setTextSize(14f);
        statusText.setPadding(pad, 0, pad, dp(8));

        mapView = new FloorMapView(this);

        detailText = new TextView(this);
        detailText.setTextSize(13f);
        detailText.setPadding(pad, pad, pad, pad);
        ScrollView details = new ScrollView(this);
        details.addView(detailText);

        root.addView(toolbar);
        root.addView(positionText);
        root.addView(statusText);
        root.addView(mapView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(details, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(180)));
        setContentView(root);
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
            positionText.setText("위치: -");
            statusText.setText("매칭된 AP가 없습니다.");
            detailText.setText("감지된 Wi-Fi: " + position.wifiCount + "개\nap_coordinates.csv의 BSSID와 현재 스캔 결과를 확인하세요.");
            mapView.showPosition(position.floor, null, position.matched);
            return;
        }

        latestMatched = position.matched;
        PdrTracker.State fused = pdrTracker == null
                ? null
                : pdrTracker.correctWithWifi(position.floor, position.estimate.x, position.estimate.y);
        double displayX = fused == null ? position.estimate.x : fused.x;
        double displayY = fused == null ? position.estimate.y : fused.y;
        WifiPositioning.Estimate displayEstimate = new WifiPositioning.Estimate(displayX, displayY, "pdr_wifi_fused");

        positionText.setText(String.format(Locale.US, "위치: %d층 (%.2f, %.2f)", position.floor, displayX, displayY));
        statusText.setText("마지막 갱신: " + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()));
        detailText.setText(buildDetails(position));
        mapView.showPosition(position.floor, displayEstimate, position.matched);
    }

    private void showPdrPosition(PdrTracker.State state) {
        if (state == null) {
            return;
        }
        WifiPositioning.Estimate estimate = new WifiPositioning.Estimate(state.x, state.y, "pdr_step");
        positionText.setText(String.format(Locale.US, "위치: %d층 (%.2f, %.2f)", state.floor, state.x, state.y));
        mapView.showPosition(state.floor, estimate, latestMatched);
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
}
