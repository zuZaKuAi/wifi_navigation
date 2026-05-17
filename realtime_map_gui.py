import argparse
import queue
import threading
import time
import tkinter as tk
from pathlib import Path
from tkinter import ttk

from PIL import Image, ImageDraw, ImageFont, ImageTk

import scan
from show_position_on_map import MAP_CONFIG, map_to_pixel


REFRESH_SECONDS = 3.0
MAX_DISPLAY_WIDTH = 1100
MAX_DISPLAY_HEIGHT = 760


class RealtimeMapApp:
    def __init__(self, root, refresh_seconds=REFRESH_SECONDS):
        self.root = root
        self.refresh_seconds = refresh_seconds
        self.result_queue = queue.Queue()
        self.worker_running = False
        self.running = True
        self.base_images = {}
        self.photo = None
        self.next_scan_id = None

        self.root.title("실시간 위치 지도")
        self.root.minsize(900, 620)

        self.floor_var = tk.StringVar(value="auto")
        self.status_var = tk.StringVar(value="대기 중")
        self.position_var = tk.StringVar(value="위치: -")
        self.scan_var = tk.StringVar(value=f"갱신 주기: {self.refresh_seconds:.1f}초")

        self._build_ui()
        self._schedule_scan(immediate=True)
        self._poll_results()
        self.root.protocol("WM_DELETE_WINDOW", self._on_close)

    def _build_ui(self):
        self.root.columnconfigure(0, weight=1)
        self.root.rowconfigure(1, weight=1)

        toolbar = ttk.Frame(self.root, padding=(12, 10))
        toolbar.grid(row=0, column=0, sticky="ew")
        toolbar.columnconfigure(8, weight=1)

        ttk.Label(toolbar, text="층").grid(row=0, column=0, padx=(0, 6))
        floor_box = ttk.Combobox(
            toolbar,
            textvariable=self.floor_var,
            values=("auto", "3", "4", "5"),
            width=8,
            state="readonly",
        )
        floor_box.grid(row=0, column=1, padx=(0, 10))
        floor_box.bind("<<ComboboxSelected>>", lambda _event: self._schedule_scan(immediate=True))

        ttk.Button(toolbar, text="지금 갱신", command=lambda: self._schedule_scan(immediate=True)).grid(
            row=0, column=2, padx=(0, 8)
        )
        ttk.Button(toolbar, text="시작", command=self._start).grid(row=0, column=3, padx=(0, 4))
        ttk.Button(toolbar, text="정지", command=self._stop).grid(row=0, column=4, padx=(0, 12))

        ttk.Label(toolbar, textvariable=self.scan_var).grid(row=0, column=5, padx=(0, 14))
        ttk.Label(toolbar, textvariable=self.position_var).grid(row=0, column=6, padx=(0, 14))
        ttk.Label(toolbar, textvariable=self.status_var).grid(row=0, column=7, sticky="w")

        content = ttk.Frame(self.root, padding=(12, 0, 12, 12))
        content.grid(row=1, column=0, sticky="nsew")
        content.columnconfigure(0, weight=1)
        content.columnconfigure(1, minsize=260)
        content.rowconfigure(0, weight=1)

        self.canvas = tk.Canvas(content, bg="#f4f5f7", highlightthickness=0)
        self.canvas.grid(row=0, column=0, sticky="nsew")
        self.canvas.bind("<Configure>", lambda _event: self._redraw_last())

        side = ttk.Frame(content, padding=(10, 0, 0, 0))
        side.grid(row=0, column=1, sticky="nsew")
        side.rowconfigure(1, weight=1)

        ttk.Label(side, text="사용된 AP").grid(row=0, column=0, sticky="w", pady=(0, 6))
        self.ap_list = tk.Listbox(side, width=38, height=18)
        self.ap_list.grid(row=1, column=0, sticky="nsew")

        ttk.Label(side, text="상태").grid(row=2, column=0, sticky="w", pady=(12, 4))
        self.detail_text = tk.Text(side, width=38, height=10, wrap="word")
        self.detail_text.grid(row=3, column=0, sticky="ew")
        self.detail_text.configure(state="disabled")

    def _start(self):
        self.running = True
        self.status_var.set("실시간 갱신 중")
        self._schedule_scan(immediate=True)

    def _stop(self):
        self.running = False
        self._cancel_next_scan()
        self.status_var.set("정지됨")

    def _on_close(self):
        self.running = False
        self.root.destroy()

    def _schedule_scan(self, immediate=False):
        if immediate and self.running:
            self._cancel_next_scan()
        elif not immediate:
            self.next_scan_id = None

        if immediate or self.running:
            self._request_scan()

        if self.running:
            self.next_scan_id = self.root.after(int(self.refresh_seconds * 1000), self._schedule_scan)

    def _cancel_next_scan(self):
        if self.next_scan_id is not None:
            self.root.after_cancel(self.next_scan_id)
            self.next_scan_id = None

    def _request_scan(self):
        if self.worker_running:
            return
        self.worker_running = True
        self.status_var.set("Wi-Fi 스캔 중...")
        threading.Thread(target=self._scan_worker, daemon=True).start()

    def _scan_worker(self):
        try:
            floor_choice = self.floor_var.get()
            requested_floor = None if floor_choice == "auto" else int(floor_choice)
            result = get_current_position(requested_floor)
            self.result_queue.put(("ok", result))
        except Exception as exc:
            self.result_queue.put(("error", exc))
        finally:
            self.worker_running = False

    def _poll_results(self):
        try:
            while True:
                status, payload = self.result_queue.get_nowait()
                if status == "ok":
                    self._show_result(payload)
                else:
                    self._show_error(payload)
        except queue.Empty:
            pass
        self.root.after(150, self._poll_results)

    def _show_result(self, result):
        self.last_result = result
        estimate = result["estimate"]
        floor = result["floor"]
        matched = result["matched"]
        wifi_count = len(result["wifi_list"])

        if estimate is None:
            self.status_var.set("위치 추정 실패")
            self.position_var.set("위치: -")
            self._set_detail(
                "매칭된 AP가 없습니다.\n"
                f"감지된 Wi-Fi: {wifi_count}개\n"
                "ap_coordinates.csv의 BSSID와 현재 스캔 결과를 확인하세요."
            )
            self._set_ap_list([])
            return

        self.position_var.set(f"위치: {floor}층 ({estimate['x']:.2f}, {estimate['y']:.2f})")
        self.status_var.set(time.strftime("마지막 갱신: %H:%M:%S"))
        self._set_ap_list(matched)
        self._set_detail(
            f"SSID: {scan.TARGET_SSID}\n"
            f"감지된 Wi-Fi: {wifi_count}개\n"
            f"매칭 AP: {len(matched)}개\n"
            f"추정 방식: {estimate['method']}\n"
            f"좌표 단위: 1칸 = {scan.CELL_SIZE_M:.3f}m"
        )
        self._draw_map(floor, estimate["x"], estimate["y"], matched)

    def _show_error(self, exc):
        self.status_var.set("오류 발생")
        self.position_var.set("위치: -")
        self._set_detail(str(exc))

    def _set_ap_list(self, matched):
        self.ap_list.delete(0, tk.END)
        for item in matched:
            self.ap_list.insert(
                tk.END,
                f"{item['ap']}  {item['rssi_dbm']:.1f} dBm  ({item['x']:.0f}, {item['y']:.0f})",
            )

    def _set_detail(self, text):
        self.detail_text.configure(state="normal")
        self.detail_text.delete("1.0", tk.END)
        self.detail_text.insert("1.0", text)
        self.detail_text.configure(state="disabled")

    def _load_base_image(self, floor):
        if floor not in self.base_images:
            config = MAP_CONFIG[floor]
            image_path = Path(__file__).with_name(config["file"])
            self.base_images[floor] = Image.open(image_path).convert("RGBA")
        return self.base_images[floor]

    def _draw_map(self, floor, x, y, matched):
        image = self._load_base_image(floor).copy()
        overlay = Image.new("RGBA", image.size, (255, 255, 255, 0))
        draw = ImageDraw.Draw(overlay)
        font = ImageFont.load_default()

        for ap in matched:
            px, py = map_to_pixel(ap["x"], ap["y"], floor)
            radius = 7
            draw.ellipse((px - radius, py - radius, px + radius, py + radius), fill=(20, 94, 180, 175))
            draw.text((px + 9, py - 10), ap["ap"], fill=(20, 94, 180, 235), font=font)

        px, py = map_to_pixel(x, y, floor)
        marker_r = 18
        draw.ellipse(
            (px - marker_r, py - marker_r, px + marker_r, py + marker_r),
            fill=(226, 45, 75, 235),
            outline=(255, 255, 255, 255),
            width=5,
        )
        draw.ellipse((px - 5, py - 5, px + 5, py + 5), fill=(255, 255, 255, 255))

        label = f"현재 위치 ({x:.2f}, {y:.2f})"
        box = draw.textbbox((0, 0), label, font=font)
        label_w = box[2] - box[0]
        label_h = box[3] - box[1]
        label_x = min(max(px + 24, 8), image.width - label_w - 18)
        label_y = min(max(py - 38, 8), image.height - label_h - 14)
        draw.rounded_rectangle(
            (label_x - 8, label_y - 6, label_x + label_w + 8, label_y + label_h + 6),
            radius=6,
            fill=(255, 255, 255, 230),
            outline=(226, 45, 75, 230),
            width=2,
        )
        draw.text((label_x, label_y), label, fill=(35, 35, 35, 255), font=font)

        self.rendered_image = Image.alpha_composite(image, overlay).convert("RGB")
        self._render_to_canvas()

    def _redraw_last(self):
        if hasattr(self, "rendered_image"):
            self._render_to_canvas()

    def _render_to_canvas(self):
        canvas_w = max(self.canvas.winfo_width(), 1)
        canvas_h = max(self.canvas.winfo_height(), 1)
        image = self.rendered_image
        scale = min(canvas_w / image.width, canvas_h / image.height, MAX_DISPLAY_WIDTH / image.width, MAX_DISPLAY_HEIGHT / image.height)
        display_size = (max(int(image.width * scale), 1), max(int(image.height * scale), 1))
        resized = image.resize(display_size, Image.Resampling.LANCZOS)
        self.photo = ImageTk.PhotoImage(resized)
        self.canvas.delete("all")
        self.canvas.create_image(canvas_w // 2, canvas_h // 2, image=self.photo, anchor="center")


def get_current_position(requested_floor=None):
    ap_coords = scan.load_ap_coordinates()
    wifi_list = scan.scan_wifi_windows(scan.TARGET_SSID)
    all_matches = scan.match_measurements(wifi_list, ap_coords, floor=None, limit=20)

    if requested_floor is None:
        floor = scan.choose_floor(all_matches) if all_matches else None
    else:
        floor = requested_floor

    matched = scan.match_measurements(wifi_list, ap_coords, floor=floor, limit=6) if floor else []
    estimate = scan.estimate_position(matched)
    return {
        "floor": floor,
        "wifi_list": wifi_list,
        "matched": matched,
        "estimate": estimate,
    }


def parse_args():
    parser = argparse.ArgumentParser(description="scan.py의 Wi-Fi 추정 위치를 지도 GUI에 실시간 표시합니다.")
    parser.add_argument("--interval", type=float, default=REFRESH_SECONDS, help="위치 갱신 주기(초)")
    return parser.parse_args()


def main():
    args = parse_args()
    root = tk.Tk()
    RealtimeMapApp(root, refresh_seconds=max(args.interval, 1.0))
    root.mainloop()


if __name__ == "__main__":
    main()
