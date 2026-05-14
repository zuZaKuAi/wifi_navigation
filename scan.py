import csv
import math
import re
import subprocess
from pathlib import Path


TARGET_SSID = "GC_free_WiFi"
AP_COORDS_FILE = Path(__file__).with_name("ap_coordinates.csv")

# 실내 RSSI 거리 추정 파라미터입니다. 실제 건물에서 보정하면 정확도가 좋아집니다.
RSSI_AT_1M = -45.0
PATH_LOSS_EXPONENT = 3.0
CELL_SIZE_M = 2.475

# None이면 RSSI가 가장 많이/강하게 잡히는 층을 자동 선택합니다.
TARGET_FLOOR = None
POSITION_METHOD = "weighted_centroid"


def normalize_bssid(value):
    hex_digits = re.sub(r"[^0-9a-fA-F]", "", value).lower()
    if len(hex_digits) != 12:
        return value.strip().lower()
    return ":".join(hex_digits[i : i + 2] for i in range(0, 12, 2))


def signal_to_dbm(signal_percent):
    return (signal_percent / 2) - 100


def rssi_to_distance(rssi_dbm):
    return 10 ** ((RSSI_AT_1M - rssi_dbm) / (10 * PATH_LOSS_EXPONENT))


def load_ap_coordinates(path=AP_COORDS_FILE):
    aps = {}
    with path.open("r", encoding="utf-8-sig", newline="") as f:
        for row in csv.DictReader(f):
            bssid = normalize_bssid(row["bssid"])
            aps[bssid] = {
                "floor": int(row["floor"]),
                "ap": row["ap"],
                "room": row["room"],
                "bssid": bssid,
                "x": float(row["x"]),
                "y": float(row["y"]),
            }
    return aps


def scan_wifi_windows(target_ssid=None):
    result = subprocess.run(
        ["netsh", "wlan", "show", "networks", "mode=bssid"],
        capture_output=True,
        text=True,
        encoding="cp949",
        errors="ignore",
    )

    output = result.stdout
    networks = []
    current_ssid = None
    current_bssid = None
    current_channel = None
    current_signal = None

    def add_current_network():
        if not current_ssid or not current_bssid or current_signal is None:
            return

        if target_ssid is None or current_ssid == target_ssid:
            networks.append(
                {
                    "ssid": current_ssid,
                    "bssid": normalize_bssid(current_bssid),
                    "signal_percent": current_signal,
                    "rssi_dbm": signal_to_dbm(current_signal),
                    "channel": current_channel,
                }
            )

    for line in output.splitlines():
        line = line.strip()

        ssid_match = re.match(r"SSID\s+\d+\s*:\s*(.*)", line)
        if ssid_match:
            add_current_network()
            current_ssid = ssid_match.group(1).strip()
            current_bssid = None
            current_channel = None
            current_signal = None
            continue

        bssid_match = re.match(r"BSSID\s+\d+\s*:\s*([0-9a-fA-F:]+)", line)
        if bssid_match:
            add_current_network()
            current_bssid = bssid_match.group(1).strip()
            current_channel = None
            current_signal = None
            continue

        label, sep, value = line.partition(":")
        label = label.strip().lower()
        value = value.strip()

        if sep and ("channel" in label or "채널" in label):
            channel_match = re.search(r"\d+", value)
            if channel_match:
                current_channel = int(channel_match.group(0))
            continue

        if sep and ("signal" in label or "신호" in label):
            signal_match = re.search(r"(\d+)\s*%", value)
            if signal_match and current_ssid and current_bssid:
                current_signal = int(signal_match.group(1))

    add_current_network()

    networks.sort(key=lambda x: x["rssi_dbm"], reverse=True)
    return networks


def choose_floor(measurements):
    floor_scores = {}
    for item in measurements:
        score = 10 ** (item["rssi_dbm"] / 10)
        floor_scores[item["floor"]] = floor_scores.get(item["floor"], 0.0) + score
    return max(floor_scores, key=floor_scores.get)


def estimate_position(measurements):
    if not measurements:
        return None

    if len(measurements) == 1:
        ap = measurements[0]
        return {
            "x": ap["x"],
            "y": ap["y"],
            "method": "nearest_ap",
            "error": 0.0,
        }

    if POSITION_METHOD == "weighted_centroid":
        return estimate_position_weighted_centroid(measurements)

    return estimate_position_trilateration(measurements)


def estimate_position_weighted_centroid(measurements):
    weighted_x = 0.0
    weighted_y = 0.0
    total_weight = 0.0

    for ap in measurements:
        # Strong nearby APs should dominate. Distance is in meters, so square it.
        weight = 1.0 / max(ap["distance"], 1.0) ** 2
        weighted_x += ap["x"] * weight
        weighted_y += ap["y"] * weight
        total_weight += weight

    return {
        "x": weighted_x / total_weight,
        "y": weighted_y / total_weight,
        "method": "rssi_weighted_centroid",
        "error": 0.0,
    }


def estimate_position_trilateration(measurements):
    min_x = min(ap["x"] for ap in measurements) - 5
    max_x = max(ap["x"] for ap in measurements) + 5
    min_y = min(ap["y"] for ap in measurements) - 5
    max_y = max(ap["y"] for ap in measurements) + 5

    def loss(x, y):
        total = 0.0
        for ap in measurements:
            predicted = math.hypot(x - ap["x"], y - ap["y"]) * CELL_SIZE_M
            weight = 1.0 / max(ap["distance"], 1.0)
            total += weight * (predicted - ap["distance"]) ** 2
        return total

    best = None
    step = 1.0
    while step >= 0.05:
        if best is None:
            xs = frange(min_x, max_x, step)
            ys = frange(min_y, max_y, step)
        else:
            xs = frange(best["x"] - step * 4, best["x"] + step * 4, step)
            ys = frange(best["y"] - step * 4, best["y"] + step * 4, step)

        for x in xs:
            for y in ys:
                value = loss(x, y)
                if best is None or value < best["error"]:
                    best = {"x": x, "y": y, "error": value}
        step /= 2

    best["method"] = "rssi_weighted_trilateration"
    return best


def frange(start, stop, step):
    value = start
    while value <= stop + 1e-9:
        yield round(value, 4)
        value += step


def match_measurements(wifi_list, ap_coords, floor=None, limit=6):
    matched = []
    for wifi in wifi_list:
        ap = ap_coords.get(wifi["bssid"])
        if not ap:
            continue
        if floor is not None and ap["floor"] != floor:
            continue

        item = {**wifi, **ap}
        item["distance"] = rssi_to_distance(wifi["rssi_dbm"])
        matched.append(item)

    matched.sort(key=lambda x: x["rssi_dbm"], reverse=True)
    return matched[:limit]


def print_scan_result(wifi_list, matched, estimate, floor):
    print(f"{TARGET_SSID} RSSI 스캔 및 위치 추정 결과")
    print("-" * 80)

    if not wifi_list:
        print("감지된 Wi-Fi가 없습니다.")
        return

    if not matched:
        print("스캔된 BSSID 중 ap_coordinates.csv와 매칭되는 AP가 없습니다.")
        print("상위 스캔 결과:")
        for wifi in wifi_list[:10]:
            print(f"- {wifi['bssid']} RSSI {wifi['rssi_dbm']:.1f} dBm")
        return

    print(f"추정 층: {floor}층")
    print(f"추정 좌표: ({estimate['x']:.2f}, {estimate['y']:.2f})")
    print(f"좌표 스케일: 1칸 = {CELL_SIZE_M:.3f}m")
    print(f"추정 방식: {estimate['method']}")
    print()
    print("사용한 AP:")
    for item in matched:
        print(
            f"- {item['ap']} {item['room']} "
            f"BSSID {item['bssid']} 좌표 ({item['x']:.0f}, {item['y']:.0f}) "
            f"RSSI {item['rssi_dbm']:.1f} dBm 거리추정 {item['distance']:.2f}m"
        )


def main():
    ap_coords = load_ap_coordinates()
    wifi_list = scan_wifi_windows(TARGET_SSID)
    all_matches = match_measurements(wifi_list, ap_coords, floor=None, limit=20)

    if TARGET_FLOOR is None and all_matches:
        floor = choose_floor(all_matches)
    else:
        floor = TARGET_FLOOR

    matched = match_measurements(wifi_list, ap_coords, floor=floor, limit=6)
    estimate = estimate_position(matched)
    print_scan_result(wifi_list, matched, estimate, floor)


if __name__ == "__main__":
    main()
