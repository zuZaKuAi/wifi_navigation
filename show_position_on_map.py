import argparse
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

import scan


# The thick dot at the lower-left of each map is coordinate (0, 0).
# pixel_per_unit is the grid spacing in the image.
MAP_CONFIG = {
    3: {"file": "3fmap.png", "origin": (355, 1083), "pixel_per_unit": 31.0},
    4: {"file": "4fmap.png", "origin": (339, 1064), "pixel_per_unit": 31.0},
    5: {"file": "5fmap.png", "origin": (356, 1129), "pixel_per_unit": 31.0},
}


def map_to_pixel(x, y, floor):
    config = MAP_CONFIG[floor]
    origin_x, origin_y = config["origin"]
    scale = config["pixel_per_unit"]
    return origin_x + x * scale, origin_y - y * scale


def draw_position_on_map(floor, x, y, matched=None, output_path=None, show=True):
    if floor not in MAP_CONFIG:
        raise ValueError(f"{floor}층 지도 설정이 없습니다.")

    matched = matched or []
    config = MAP_CONFIG[floor]
    image_path = Path(__file__).with_name(config["file"])
    if not image_path.exists():
        raise FileNotFoundError(f"지도 파일을 찾을 수 없습니다: {image_path}")

    image = Image.open(image_path).convert("RGBA")
    overlay = Image.new("RGBA", image.size, (255, 255, 255, 0))
    draw = ImageDraw.Draw(overlay)

    for ap in matched:
        px, py = map_to_pixel(ap["x"], ap["y"], floor)
        r = 7
        draw.ellipse((px - r, py - r, px + r, py + r), fill=(25, 118, 210, 170))
        draw.text((px + 9, py - 10), ap["ap"], fill=(25, 118, 210, 230))

    px, py = map_to_pixel(x, y, floor)
    marker_r = 18
    draw.ellipse(
        (px - marker_r, py - marker_r, px + marker_r, py + marker_r),
        fill=(255, 45, 85, 235),
        outline=(255, 255, 255, 255),
        width=5,
    )
    draw.ellipse((px - 5, py - 5, px + 5, py + 5), fill=(255, 255, 255, 255))

    label = f"현재 위치 ({x:.2f}, {y:.2f})"
    font = ImageFont.load_default()
    box = draw.textbbox((0, 0), label, font=font)
    label_w = box[2] - box[0]
    label_h = box[3] - box[1]
    label_x = min(max(px + 24, 8), image.width - label_w - 18)
    label_y = min(max(py - 38, 8), image.height - label_h - 14)
    draw.rounded_rectangle(
        (label_x - 8, label_y - 6, label_x + label_w + 8, label_y + label_h + 6),
        radius=6,
        fill=(255, 255, 255, 225),
        outline=(255, 45, 85, 230),
        width=2,
    )
    draw.text((label_x, label_y), label, fill=(40, 40, 40, 255), font=font)

    result = Image.alpha_composite(image, overlay).convert("RGB")
    if output_path is None:
        output_path = Path(__file__).with_name(f"position_{floor}fmap.png")
    else:
        output_path = Path(output_path)

    result.save(output_path)
    if show:
        result.show()
    return output_path


def parse_args():
    parser = argparse.ArgumentParser(
        description="scan.py로 측정한 좌표 또는 직접 입력한 좌표를 지도 위에 표시합니다."
    )
    parser.add_argument("--floor", type=int, choices=MAP_CONFIG.keys(), help="표시할 층")
    parser.add_argument("--x", type=float, help="표시할 x 좌표")
    parser.add_argument("--y", type=float, help="표시할 y 좌표")
    parser.add_argument("--no-show", action="store_true", help="이미지 창을 열지 않고 저장만 합니다.")
    parser.add_argument("--output", help="저장할 이미지 경로")
    return parser.parse_args()


def main():
    args = parse_args()

    if args.floor is not None and args.x is not None and args.y is not None:
        floor = args.floor
        x = args.x
        y = args.y
        matched = []
    else:
        ap_coords = scan.load_ap_coordinates()
        wifi_list = scan.scan_wifi_windows(scan.TARGET_SSID)
        all_matches = scan.match_measurements(wifi_list, ap_coords, floor=None, limit=20)

        if scan.TARGET_FLOOR is None and all_matches:
            floor = scan.choose_floor(all_matches)
        else:
            floor = scan.TARGET_FLOOR

        matched = scan.match_measurements(wifi_list, ap_coords, floor=floor, limit=6)
        estimate = scan.estimate_position(matched)
        scan.print_scan_result(wifi_list, matched, estimate, floor)
        if estimate is None:
            return
        x = estimate["x"]
        y = estimate["y"]

    output_path = draw_position_on_map(
        floor=floor,
        x=x,
        y=y,
        matched=matched,
        output_path=args.output,
        show=not args.no_show,
    )
    print(f"지도 표시 파일: {output_path}")


if __name__ == "__main__":
    main()
