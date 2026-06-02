package com.example.wifinavigation;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;

final class NavigationGraph {
    enum ConnectorType {
        STAIRS,
        ELEVATOR
    }

    private static final int CELL_PX = 4;
    private static final int WALKABLE = 1;
    private static final int STAIRS = 2;
    private static final int ELEVATOR = 4;

    private final Context context;
    private final Map<Integer, FloorMask> masks = new HashMap<>();

    NavigationGraph(Context context) {
        this.context = context.getApplicationContext();
    }

    Point snapToCorridor(int floor, double x, double y) {
        FloorMask mask = loadMask(floor);
        return mask == null ? new Point(floor, x, y) : mask.snap(x, y);
    }

    Route route(int floor, Point start, Point end) {
        FloorMask mask = loadMask(floor);
        if (mask == null || start == null || end == null) {
            return Route.empty(floor);
        }
        return mask.route(start.x, start.y, end.x, end.y);
    }

    Point nearestConnector(int floor, Point from, ConnectorType type) {
        FloorMask mask = loadMask(floor);
        if (mask == null || from == null) {
            return null;
        }
        return mask.nearestConnector(from.x, from.y, type);
    }

    Route routeToNearestConnector(int floor, Point from, ConnectorType type) {
        FloorMask mask = loadMask(floor);
        if (mask == null || from == null) {
            return Route.empty(floor);
        }
        return mask.routeToNearestConnector(from.x, from.y, type);
    }

    WifiPositioning.ApInfo findDestination(Iterable<WifiPositioning.ApInfo> aps, String query) {
        if (aps == null || query == null) {
            return null;
        }
        String normalized = query.trim().toLowerCase(Locale.US);
        if (normalized.isEmpty()) {
            return null;
        }

        WifiPositioning.ApInfo partial = null;
        for (WifiPositioning.ApInfo ap : aps) {
            String room = ap.room == null ? "" : ap.room.trim().toLowerCase(Locale.US);
            if (room.equals(normalized)) {
                return ap;
            }
            if (partial == null && room.contains(normalized)) {
                partial = ap;
            }
        }
        return partial;
    }

    private FloorMask loadMask(int floor) {
        FloorMask cached = masks.get(floor);
        if (cached != null) {
            return cached;
        }
        FloorConfig config = FloorConfig.CONFIGS.get(floor);
        if (config == null) {
            return null;
        }
        try {
            Bitmap bitmap = BitmapFactory.decodeStream(context.getAssets().open(config.corridorFile));
            FloorMask mask = FloorMask.fromBitmap(config, bitmap);
            masks.put(floor, mask);
            return mask;
        } catch (IOException e) {
            return null;
        }
    }

    static final class Point {
        final int floor;
        final double x;
        final double y;

        Point(int floor, double x, double y) {
            this.floor = floor;
            this.x = x;
            this.y = y;
        }
    }

    static final class Route {
        final int floor;
        final List<Point> points;

        Route(int floor, List<Point> points) {
            this.floor = floor;
            this.points = points;
        }

        static Route empty(int floor) {
            return new Route(floor, Collections.emptyList());
        }
    }

    private static final class FloorMask {
        final FloorConfig config;
        final int cols;
        final int rows;
        final byte[] cells;
        final List<Integer> stairCells;
        final List<Integer> elevatorCells;

        private FloorMask(
                FloorConfig config,
                int cols,
                int rows,
                byte[] cells,
                List<Integer> stairCells,
                List<Integer> elevatorCells
        ) {
            this.config = config;
            this.cols = cols;
            this.rows = rows;
            this.cells = cells;
            this.stairCells = stairCells;
            this.elevatorCells = elevatorCells;
        }

        static FloorMask fromBitmap(FloorConfig config, Bitmap bitmap) {
            int cols = (bitmap.getWidth() + CELL_PX - 1) / CELL_PX;
            int rows = (bitmap.getHeight() + CELL_PX - 1) / CELL_PX;
            byte[] cells = new byte[cols * rows];
            List<Integer> stairCells = new ArrayList<>();
            List<Integer> elevatorCells = new ArrayList<>();

            for (int gy = 0; gy < rows; gy++) {
                for (int gx = 0; gx < cols; gx++) {
                    int flags = 0;
                    int startX = gx * CELL_PX;
                    int startY = gy * CELL_PX;
                    int endX = Math.min(startX + CELL_PX, bitmap.getWidth());
                    int endY = Math.min(startY + CELL_PX, bitmap.getHeight());
                    for (int py = startY; py < endY; py++) {
                        for (int px = startX; px < endX; px++) {
                            int color = bitmap.getPixel(px, py);
                            int pixelFlags = classify(color);
                            flags |= pixelFlags;
                        }
                    }
                    if ((flags & WALKABLE) != 0) {
                        int index = index(gx, gy, cols);
                        cells[index] = (byte) flags;
                        if ((flags & STAIRS) != 0) {
                            stairCells.add(index);
                        }
                        if ((flags & ELEVATOR) != 0) {
                            elevatorCells.add(index);
                        }
                    }
                }
            }
            return new FloorMask(config, cols, rows, cells, stairCells, elevatorCells);
        }

        Point snap(double x, double y) {
            int startX = clamp(Math.round(config.unitToPixelX(x) / CELL_PX), 0, cols - 1);
            int startY = clamp(Math.round(config.unitToPixelY(y) / CELL_PX), 0, rows - 1);
            int best = nearestWalkableIndex(startX, startY);
            return best < 0 ? new Point(config.floor, x, y) : pointFor(best);
        }

        Point nearestConnector(double x, double y, ConnectorType type) {
            List<Integer> candidates = type == ConnectorType.STAIRS ? stairCells : elevatorCells;
            if (candidates.isEmpty()) {
                return null;
            }
            int best = -1;
            double bestDistance = Double.POSITIVE_INFINITY;
            double px = config.unitToPixelX(x);
            double py = config.unitToPixelY(y);
            for (int index : candidates) {
                int gx = index % cols;
                int gy = index / cols;
                double cx = cellCenterPixel(gx);
                double cy = cellCenterPixel(gy);
                double distance = Math.hypot(cx - px, cy - py);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = index;
                }
            }
            return best < 0 ? null : pointFor(best);
        }

        Route route(double startX, double startY, double endX, double endY) {
            int start = nearestWalkableIndex(
                    clamp(Math.round(config.unitToPixelX(startX) / CELL_PX), 0, cols - 1),
                    clamp(Math.round(config.unitToPixelY(startY) / CELL_PX), 0, rows - 1)
            );
            int goal = nearestWalkableIndex(
                    clamp(Math.round(config.unitToPixelX(endX) / CELL_PX), 0, cols - 1),
                    clamp(Math.round(config.unitToPixelY(endY) / CELL_PX), 0, rows - 1)
            );
            if (start < 0 || goal < 0) {
                return Route.empty(config.floor);
            }
            if (start == goal) {
                return new Route(config.floor, Collections.singletonList(pointFor(start)));
            }

            int[] previous = new int[cells.length];
            double[] cost = new double[cells.length];
            for (int i = 0; i < cells.length; i++) {
                previous[i] = -1;
                cost[i] = Double.POSITIVE_INFINITY;
            }
            PriorityQueue<SearchNode> queue = new PriorityQueue<>((a, b) -> Double.compare(a.priority, b.priority));
            cost[start] = 0.0;
            queue.add(new SearchNode(start, heuristic(start, goal)));

            while (!queue.isEmpty()) {
                SearchNode current = queue.poll();
                if (current.index == goal) {
                    break;
                }
                int gx = current.index % cols;
                int gy = current.index / cols;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) {
                            continue;
                        }
                        int nx = gx + dx;
                        int ny = gy + dy;
                        if (nx < 0 || ny < 0 || nx >= cols || ny >= rows) {
                            continue;
                        }
                        int next = index(nx, ny, cols);
                        if (!isWalkable(next)) {
                            continue;
                        }
                        double nextCost = cost[current.index] + Math.hypot(dx, dy);
                        if (nextCost < cost[next]) {
                            cost[next] = nextCost;
                            previous[next] = current.index;
                            queue.add(new SearchNode(next, nextCost + heuristic(next, goal)));
                        }
                    }
                }
            }
            if (previous[goal] < 0) {
                return Route.empty(config.floor);
            }

            ArrayDeque<Integer> reversed = new ArrayDeque<>();
            int cursor = goal;
            while (cursor >= 0) {
                reversed.addFirst(cursor);
                if (cursor == start) {
                    break;
                }
                cursor = previous[cursor];
            }
            return new Route(config.floor, simplify(reversed));
        }

        Route routeToNearestConnector(double x, double y, ConnectorType type) {
            List<Integer> candidates = type == ConnectorType.STAIRS ? stairCells : elevatorCells;
            if (candidates.isEmpty()) {
                return Route.empty(config.floor);
            }
            List<Integer> sorted = new ArrayList<>(candidates);
            double px = config.unitToPixelX(x);
            double py = config.unitToPixelY(y);
            sorted.sort((a, b) -> Double.compare(pixelDistance(a, px, py), pixelDistance(b, px, py)));
            for (int index : sorted) {
                Point connector = pointFor(index);
                Route route = route(x, y, connector.x, connector.y);
                if (route.points.size() >= 2) {
                    return route;
                }
            }
            return Route.empty(config.floor);
        }

        private double pixelDistance(int index, double px, double py) {
            int gx = index % cols;
            int gy = index / cols;
            return Math.hypot(cellCenterPixel(gx) - px, cellCenterPixel(gy) - py);
        }

        private List<Point> simplify(ArrayDeque<Integer> path) {
            List<Integer> indexes = new ArrayList<>(path);
            if (indexes.size() <= 2) {
                List<Point> points = new ArrayList<>();
                for (int index : indexes) {
                    points.add(pointFor(index));
                }
                return points;
            }

            List<Point> points = new ArrayList<>();
            points.add(pointFor(indexes.get(0)));
            int lastDx = 0;
            int lastDy = 0;
            for (int i = 1; i < indexes.size(); i++) {
                int prev = indexes.get(i - 1);
                int curr = indexes.get(i);
                int dx = Integer.compare(curr % cols, prev % cols);
                int dy = Integer.compare(curr / cols, prev / cols);
                if (i > 1 && (dx != lastDx || dy != lastDy)) {
                    points.add(pointFor(prev));
                }
                lastDx = dx;
                lastDy = dy;
            }
            points.add(pointFor(indexes.get(indexes.size() - 1)));
            return points;
        }

        private int nearestWalkableIndex(int startX, int startY) {
            int start = index(startX, startY, cols);
            if (isWalkable(start)) {
                return start;
            }
            boolean[] visited = new boolean[cells.length];
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(start);
            visited[start] = true;
            while (!queue.isEmpty()) {
                int current = queue.removeFirst();
                int gx = current % cols;
                int gy = current / cols;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) {
                            continue;
                        }
                        int nx = gx + dx;
                        int ny = gy + dy;
                        if (nx < 0 || ny < 0 || nx >= cols || ny >= rows) {
                            continue;
                        }
                        int next = index(nx, ny, cols);
                        if (visited[next]) {
                            continue;
                        }
                        if (isWalkable(next)) {
                            return next;
                        }
                        visited[next] = true;
                        queue.add(next);
                    }
                }
            }
            return -1;
        }

        private boolean isWalkable(int index) {
            return index >= 0 && index < cells.length && (cells[index] & WALKABLE) != 0;
        }

        private double heuristic(int a, int b) {
            return Math.hypot(a % cols - b % cols, a / cols - b / cols);
        }

        private Point pointFor(int index) {
            int gx = index % cols;
            int gy = index / cols;
            return new Point(
                    config.floor,
                    config.pixelToUnitX(cellCenterPixel(gx)),
                    config.pixelToUnitY(cellCenterPixel(gy))
            );
        }

        private static int classify(int color) {
            int r = Color.red(color);
            int g = Color.green(color);
            int b = Color.blue(color);
            boolean green = g > 135 && r < 130 && b < 160;
            boolean cyan = b > 170 && g > 150 && r < 140;
            boolean purple = r > 95 && b > 120 && g < 150;
            if (cyan) {
                return WALKABLE | STAIRS;
            }
            if (purple) {
                return WALKABLE | ELEVATOR;
            }
            if (green) {
                return WALKABLE;
            }
            return 0;
        }

        private static int index(int x, int y, int cols) {
            return y * cols + x;
        }

        private static float cellCenterPixel(int value) {
            return value * CELL_PX + CELL_PX / 2f;
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    private static final class SearchNode {
        final int index;
        final double priority;

        SearchNode(int index, double priority) {
            this.index = index;
            this.priority = priority;
        }
    }
}
