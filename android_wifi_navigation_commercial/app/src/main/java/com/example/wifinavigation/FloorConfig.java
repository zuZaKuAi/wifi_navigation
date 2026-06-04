package com.example.wifinavigation;

import java.util.HashMap;
import java.util.Map;

final class FloorConfig {
    static final Map<Integer, FloorConfig> CONFIGS = createConfigs();

    final int floor;
    final String mapFile;
    final String corridorFile;
    final float originX;
    final float originY;
    final float pixelPerUnit;

    private FloorConfig(int floor, String mapFile, String corridorFile, float originX, float originY, float pixelPerUnit) {
        this.floor = floor;
        this.mapFile = mapFile;
        this.corridorFile = corridorFile;
        this.originX = originX;
        this.originY = originY;
        this.pixelPerUnit = pixelPerUnit;
    }

    float unitToPixelX(double x) {
        return (float) (originX + x * pixelPerUnit);
    }

    float unitToPixelY(double y) {
        return (float) (originY - y * pixelPerUnit);
    }

    double pixelToUnitX(double px) {
        return (px - originX) / pixelPerUnit;
    }

    double pixelToUnitY(double py) {
        return (originY - py) / pixelPerUnit;
    }

    private static Map<Integer, FloorConfig> createConfigs() {
        Map<Integer, FloorConfig> configs = new HashMap<>();
        configs.put(3, new FloorConfig(3, "new3fmap_clean.png", "corridor_3f.png", 355f, 1083f, 31f));
        configs.put(4, new FloorConfig(4, "new4fmap_clean.png", "corridor_4f.png", 339f, 1064f, 31f));
        configs.put(5, new FloorConfig(5, "new5fmap_clean.png", "corridor_5f.png", 356f, 1129f, 31f));
        return configs;
    }
}
