package com.example.wifinavigation;

import android.content.Context;
import android.net.wifi.ScanResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class WifiPositioning {
    static final String TARGET_SSID = "GC_free_WiFi";
    static final double RSSI_AT_1M = -45.0;
    static final double PATH_LOSS_EXPONENT = 3.0;
    static final double CELL_SIZE_M = 2.475;
    static final double WEIGHT_DISTANCE_EXPONENT = 1.2;
    static final double MIN_WEIGHT_DISTANCE_M = 2.0;

    private WifiPositioning() {
    }

    static Map<String, ApInfo> loadApCoordinates(Context context) throws IOException {
        Map<String, ApInfo> aps = new HashMap<>();
        for (ApInfo ap : loadApCoordinateList(context)) {
            aps.put(ap.bssid, ap);
        }
        return aps;
    }

    static List<ApInfo> loadApCoordinateList(Context context) throws IOException {
        List<ApInfo> aps = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open("ap_coordinates.csv"), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            while ((line = reader.readLine()) != null) {
                List<String> cols = parseCsvLine(line);
                if (cols.size() < 6) {
                    continue;
                }
                String bssid = normalizeBssid(cols.get(3));
                aps.add(new ApInfo(
                        Integer.parseInt(cols.get(0).trim()),
                        cols.get(1).trim(),
                        cols.get(2).trim(),
                        bssid,
                        Double.parseDouble(cols.get(4).trim()),
                        Double.parseDouble(cols.get(5).trim())
                ));
            }
        }
        return aps;
    }

    static PositionResult estimate(List<ScanResult> scanResults, Map<String, ApInfo> apCoordinates, Integer requestedFloor) {
        List<MatchedAp> allMatches = matchMeasurements(scanResults, apCoordinates, null, 20);
        Integer floor = requestedFloor != null ? requestedFloor : chooseFloor(allMatches);
        List<MatchedAp> matched = floor == null
                ? Collections.emptyList()
                : matchMeasurements(scanResults, apCoordinates, floor, 6);
        Estimate estimate = estimatePosition(matched);
        return new PositionResult(floor, matched, estimate, scanResults.size());
    }

    static List<MatchedAp> matchMeasurements(
            List<ScanResult> scanResults,
            Map<String, ApInfo> apCoordinates,
            Integer floor,
            int limit
    ) {
        List<MatchedAp> matched = new ArrayList<>();
        for (ScanResult result : scanResults) {
            if (!TARGET_SSID.equals(result.SSID)) {
                continue;
            }
            ApInfo ap = apCoordinates.get(normalizeBssid(result.BSSID));
            if (ap == null) {
                continue;
            }
            if (floor != null && ap.floor != floor) {
                continue;
            }
            matched.add(new MatchedAp(ap, result.level, rssiToDistance(result.level)));
        }
        matched.sort((a, b) -> Integer.compare(b.rssiDbm, a.rssiDbm));
        if (matched.size() > limit) {
            return new ArrayList<>(matched.subList(0, limit));
        }
        return matched;
    }

    private static Integer chooseFloor(List<MatchedAp> measurements) {
        if (measurements.isEmpty()) {
            return null;
        }
        Map<Integer, Double> scores = new HashMap<>();
        for (MatchedAp item : measurements) {
            double score = Math.pow(10.0, item.rssiDbm / 10.0);
            Double current = scores.get(item.ap.floor);
            scores.put(item.ap.floor, (current == null ? 0.0 : current) + score);
        }
        Integer bestFloor = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Map.Entry<Integer, Double> entry : scores.entrySet()) {
            if (entry.getValue() > bestScore) {
                bestScore = entry.getValue();
                bestFloor = entry.getKey();
            }
        }
        return bestFloor;
    }

    private static Estimate estimatePosition(List<MatchedAp> measurements) {
        if (measurements.isEmpty()) {
            return null;
        }
        if (measurements.size() == 1) {
            ApInfo ap = measurements.get(0).ap;
            return new Estimate(ap.x, ap.y, "nearest_ap");
        }

        double weightedX = 0.0;
        double weightedY = 0.0;
        double totalWeight = 0.0;
        for (MatchedAp item : measurements) {
            double weight = 1.0 / Math.pow(Math.max(item.distanceM, MIN_WEIGHT_DISTANCE_M), WEIGHT_DISTANCE_EXPONENT);
            weightedX += item.ap.x * weight;
            weightedY += item.ap.y * weight;
            totalWeight += weight;
        }
        return new Estimate(weightedX / totalWeight, weightedY / totalWeight, "rssi_weighted_centroid");
    }

    private static double rssiToDistance(double rssiDbm) {
        return Math.pow(10.0, (RSSI_AT_1M - rssiDbm) / (10.0 * PATH_LOSS_EXPONENT));
    }

    static String normalizeBssid(String value) {
        String hex = value == null ? "" : value.replaceAll("[^0-9a-fA-F]", "").toLowerCase(Locale.US);
        if (hex.length() != 12) {
            return value == null ? "" : value.trim().toLowerCase(Locale.US);
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) {
                builder.append(':');
            }
            builder.append(hex, i, i + 2);
        }
        return builder.toString();
    }

    private static List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        values.add(current.toString());
        return values;
    }

    static final class ApInfo {
        final int floor;
        final String ap;
        final String room;
        final String bssid;
        final double x;
        final double y;

        ApInfo(int floor, String ap, String room, String bssid, double x, double y) {
            this.floor = floor;
            this.ap = ap;
            this.room = room;
            this.bssid = bssid;
            this.x = x;
            this.y = y;
        }
    }

    static final class MatchedAp {
        final ApInfo ap;
        final int rssiDbm;
        final double distanceM;

        MatchedAp(ApInfo ap, int rssiDbm, double distanceM) {
            this.ap = ap;
            this.rssiDbm = rssiDbm;
            this.distanceM = distanceM;
        }
    }

    static final class Estimate {
        final double x;
        final double y;
        final String method;

        Estimate(double x, double y, String method) {
            this.x = x;
            this.y = y;
            this.method = method;
        }
    }

    static final class PositionResult {
        final Integer floor;
        final List<MatchedAp> matched;
        final Estimate estimate;
        final int wifiCount;

        PositionResult(Integer floor, List<MatchedAp> matched, Estimate estimate, int wifiCount) {
            this.floor = floor;
            this.matched = matched;
            this.estimate = estimate;
            this.wifiCount = wifiCount;
        }
    }
}
