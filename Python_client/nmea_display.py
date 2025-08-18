import threading
import queue
import time
import math
import json
import os
import tkinter as tk
from tkinter import ttk, messagebox

import serial
from serial.tools import list_ports

SETTINGS_FILE = "nmea_gui_settings.json"
DEFAULTS = {
    "port": "",
    "baud": 38400,
    "fir_window": 15,
    "heading_offset_deg": 0.0,
}

# ---------------- Utility & settings ----------------
def load_settings():
    s = DEFAULTS.copy()
    if os.path.exists(SETTINGS_FILE):
        try:
            with open(SETTINGS_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
            s.update({k: data.get(k, v) for k, v in DEFAULTS.items()})
        except Exception:
            pass
    return s


def save_settings(s):
    try:
        with open(SETTINGS_FILE, "w", encoding="utf-8") as f:
            json.dump({k: s.get(k, DEFAULTS[k]) for k in DEFAULTS}, f, indent=2)
        return True, None
    except Exception as e:
        return False, str(e)


def nmea_checksum_ok(sentence: str) -> bool:
    if "*" not in sentence or not sentence.startswith("$"):
        return False
    try:
        data, cs = sentence.strip()[1:].split("*", 1)
        calc = 0
        for ch in data:
            calc ^= ord(ch)
        return f"{calc:02X}" == cs[:2].upper()
    except Exception:
        return False


def split_fields(sentence: str):
    s = sentence.strip()
    if not s.startswith("$"):
        return None, None, []
    star = s.find("*")
    body = s[1: star if star >= 0 else None]
    parts = body.split(",")
    if not parts or len(parts[0]) < 5:
        return None, None, []
    talker = parts[0][:2]
    typ = parts[0][2:]
    return talker, typ, parts[1:]


def safe_int(s):
    try:
        return int(s)
    except Exception:
        return None


def safe_float(s):
    try:
        return float(s)
    except Exception:
        return None


def norm360(deg):
    return (deg % 360.0 + 360.0) % 360.0


# --------- Circular moving average for headings ----------
class HeadingFIR:
    """
    FIR moving average on a circle (0..360). Keeps a window of angles (deg),
    uses running sums of sin/cos, returns circular mean in [0,360).
    """

    def __init__(self, window=15):
        self.window = max(1, int(window))
        self.angles = []  # radians
        self.sum_sin = 0.0
        self.sum_cos = 0.0

    def set_window(self, n):
        n = max(1, int(n))
        self.window = n
        while len(self.angles) > self.window:
            old = self.angles.pop(0)
            self.sum_sin -= math.sin(old)
            self.sum_cos -= math.cos(old)

    def reset(self):
        self.angles.clear()
        self.sum_sin = 0.0
        self.sum_cos = 0.0

    def add(self, deg):
        rad = math.radians(norm360(deg))
        self.angles.append(rad)
        self.sum_sin += math.sin(rad)
        self.sum_cos += math.cos(rad)
        if len(self.angles) > self.window:
            old = self.angles.pop(0)
            self.sum_sin -= math.sin(old)
            self.sum_cos -= math.cos(old)

    def value(self):
        if not self.angles:
            return None
        mean = math.degrees(math.atan2(self.sum_sin, self.sum_cos))
        return norm360(mean)


# --------- Live state that the GUI displays ----------
class NMEAState:
    def __init__(self):
        self.utc = None
        self.date = None
        self.fix = 0
        self.sats_used = None
        self.sats_in_view = None
        self.pdop = None
        self.hdop = None
        self.vdop = None
        self.alt_m = None
        self.heading_T = None  # raw heading (deg true)
        self.last_talker = None
        self.ok_count = 0
        self.bad_count = 0

    def as_dict(self):
        FIX_QUAL = {
            0: "No fix",
            1: "GPS",
            2: "DGPS",
            4: "RTK Fixed",
            5: "RTK Float",
            6: "Dead reckoning",
        }
        return {
            "UTC": self.utc or "--:--:--",
            "Date": self.date or "--------",
            "Talker": self.last_talker or "--",
            "Fix": FIX_QUAL.get(self.fix, str(self.fix)),
            "Sats Used": "?" if self.sats_used is None else str(self.sats_used),
            "In View": "?" if self.sats_in_view is None else str(self.sats_in_view),
            "PDOP": "?" if self.pdop is None else str(self.pdop),
            "HDOP": "?" if self.hdop is None else str(self.hdop),
            "VDOP": "?" if self.vdop is None else str(self.vdop),
            "Altitude": "?" if self.alt_m is None else f"{self.alt_m:.1f} m",
            "Heading": "?" if self.heading_T is None else f"{self.heading_T:.1f}°T",
            "Checksums": f"OK:{self.ok_count} Bad:{self.bad_count}",
        }


# --------- Serial reader thread ----------
class SerialReader(threading.Thread):
    def __init__(self, port, baud, out_queue, log_queue, stop_event, fir_window=15, heading_offset_deg=0.0):
        super().__init__(daemon=True)
        self.port = port
        self.baud = baud
        self.q = out_queue
        self.logq = log_queue
        self.stop_event = stop_event
        self.state = NMEAState()
        self.ser = None
        self.buf = bytearray()
        self.heading_fir = HeadingFIR(window=fir_window)
        self.filtered_heading = None
        self.heading_offset_deg = float(heading_offset_deg)

    def set_fir_window(self, n):
        self.heading_fir.set_window(n)

    def set_heading_offset(self, deg):
        try:
            self.heading_offset_deg = float(deg)
        except ValueError:
            pass

    def run(self):
        try:
            self.ser = serial.Serial(
                self.port,
                self.baud,
                timeout=1,
                bytesize=serial.EIGHTBITS,
                parity=serial.PARITY_NONE,
                stopbits=serial.STOPBITS_ONE,
            )
            self.logq.put(f"Opened {self.port} at {self.baud} 8N1")
        except Exception as e:
            self.logq.put(f"ERROR opening {self.port}: {e}")
            return

        last_push = 0
        try:
            while not self.stop_event.is_set():
                chunk = self.ser.read(4096)
                if chunk:
                    self.buf.extend(chunk)
                    # Split on CR/LF
                    while True:
                        i_cr = self.buf.find(b"\r")
                        i_lf = self.buf.find(b"\n")
                        idx = min([x for x in (i_cr, i_lf) if x != -1], default=-1)
                        if idx == -1:
                            break
                        line = bytes(self.buf[:idx]).decode(errors="replace").strip()
                        drop = 2 if idx + 1 < len(self.buf) and self.buf[idx:idx+2] in (b"\r\n", b"\n\r") else 1
                        del self.buf[: idx + drop]
                        if line:
                            self.handle_sentence(line)
                            self.logq.put(line)

                # push state periodically
                now = time.time()
                if now - last_push >= 0.2:
                    d = self.state.as_dict()
                    d["_filtered_heading"] = self.filtered_heading
                    d["_offset_deg"] = self.heading_offset_deg
                    # add raw values helpful for mini window if needed later
                    d["_sats_used"] = self.state.sats_used
                    d["_sats_in_view"] = self.state.sats_in_view
                    d["_hdop"] = self.state.hdop
                    self.q.put(d)
                    last_push = now

        finally:
            try:
                if self.ser and self.ser.is_open:
                    self.ser.close()
                    self.logq.put("Serial closed.")
            except Exception:
                pass

    def handle_sentence(self, sentence: str):
        if nmea_checksum_ok(sentence):
            self.state.ok_count += 1
        else:
            self.state.bad_count += 1
        talker, typ, f = split_fields(sentence)
        if talker:
            self.state.last_talker = talker

        if typ == "GGA" and len(f) >= 14:
            self.state.fix = safe_int(f[5]) or 0
            nsat = safe_int(f[6])
            if nsat is not None:
                self.state.sats_used = nsat
            if f[7]:
                v = safe_float(f[7])
                if v is not None:
                    self.state.hdop = v
            if f[8]:
                v = safe_float(f[8])
                if v is not None:
                    self.state.alt_m = v
            if f[0] and len(f[0]) >= 6:
                hh, mm, ss = f[0][0:2], f[0][2:4], f[0][4:6]
                self.state.utc = f"{hh}:{mm}:{ss}"

        elif typ == "GSA" and len(f) >= 17:
            used = sum(1 for s in f[2:14] if s.strip())
            if used > 0:
                self.state.sats_used = used
            pdop = safe_float(f[14])
            hdop = safe_float(f[15])
            vdop = safe_float(f[16])
            if pdop is not None:
                self.state.pdop = pdop
            if hdop is not None:
                self.state.hdop = hdop
            if vdop is not None:
                self.state.vdop = vdop

        elif typ == "GSV" and len(f) >= 4:
            siv = safe_int(f[2])
            if siv is not None:
                self.state.sats_in_view = siv

        elif typ == "ZDA" and len(f) >= 6:
            if f[0] and len(f[0]) >= 6:
                hh, mm, ss = f[0][0:2], f[0][2:4], f[0][4:6]
                self.state.utc = f"{hh}:{mm}:{ss}"
            year = safe_int(f[3])
            mon = safe_int(f[2])
            day = safe_int(f[1])
            if year and mon and day:
                self.state.date = f"{year:04d}-{mon:02d}-{day:02d}"

        elif typ == "HDT" and len(f) >= 2:
            hdg = safe_float(f[0])
            if hdg is not None:
                self.state.heading_T = norm360(hdg)
                # Update FIR with raw (un-offset) heading
                self.heading_fir.add(self.state.heading_T)
                # Apply offset to filtered for display
                filt = self.heading_fir.value()
                self.filtered_heading = None if filt is None else norm360(
                    filt + self.heading_offset_deg
                )


# --------- GUI ----------
class NMEAGui(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("NMEA Live Status")
        self.geometry("820x620")
        self.minsize(800, 600)

        self.out_queue = queue.Queue()
        self.log_queue = queue.Queue()
        self.stop_event = threading.Event()
        self.reader = None

        self.settings = load_settings()

        # Mini window refs
        self._mini_win = None
        self._mini_heading = None
        self._mini_status = None

        self._build_widgets()
        self._populate_ports()
        self._apply_settings_to_widgets()

        self.after(100, self._poll_queues)
        self.protocol("WM_DELETE_WINDOW", self._on_close)

    def _build_widgets(self):
        # ---------- TOP CONTROLS (split into rows) ----------
        frm_top = ttk.Frame(self, padding=8)
        frm_top.pack(side=tk.TOP, fill=tk.X)

        # Row 1: Port + Refresh + Baud + FIR + Offset
        row1 = ttk.Frame(frm_top)
        row1.pack(side=tk.TOP, fill=tk.X, pady=(0, 4))

        ttk.Label(row1, text="Port:").pack(side=tk.LEFT)
        self.cmb_port = ttk.Combobox(row1, width=12, state="readonly")
        self.cmb_port.pack(side=tk.LEFT, padx=5)
        ttk.Button(row1, text="Refresh", command=self._populate_ports).pack(side=tk.LEFT, padx=5)

        ttk.Label(row1, text="Baud:").pack(side=tk.LEFT, padx=(12, 0))
        self.cmb_baud = ttk.Combobox(
            row1,
            width=8,
            state="readonly",
            values=["4800", "9600", "19200", "38400", "57600", "115200"],
        )
        self.cmb_baud.set("38400")
        self.cmb_baud.pack(side=tk.LEFT, padx=5)

        ttk.Label(row1, text="Heading FIR window:").pack(side=tk.LEFT, padx=(12, 4))
        self.spin_win = tk.Spinbox(row1, from_=1, to=300, width=5, command=self._apply_window)
        self.spin_win.delete(0, tk.END)
        self.spin_win.insert(0, "15")
        self.spin_win.pack(side=tk.LEFT, padx=(0, 8))

        ttk.Label(row1, text="Heading offset (°):").pack(side=tk.LEFT, padx=(12, 4))
        self.spin_off = tk.Spinbox(
            row1, from_=-180, to=180, increment=0.1, width=7, command=self._apply_offset
        )
        self.spin_off.delete(0, tk.END)
        self.spin_off.insert(0, "0.0")
        self.spin_off.pack(side=tk.LEFT)

        # Row 2: Connect + Status
        row2 = ttk.Frame(frm_top)
        row2.pack(side=tk.TOP, fill=tk.X, pady=(0, 4))

        self.btn_connect = ttk.Button(row2, text="Connect", command=self._toggle_connection)
        self.btn_connect.pack(side=tk.LEFT, padx=10)

        self.lbl_status = ttk.Label(row2, text="Disconnected", foreground="red")
        self.lbl_status.pack(side=tk.LEFT, padx=10)

        # Row 3: Mini controls (left) + Save (right)
        row3 = ttk.Frame(frm_top)
        row3.pack(side=tk.TOP, fill=tk.X, pady=(0, 6))

        left3 = ttk.Frame(row3)
        left3.pack(side=tk.LEFT)
        ttk.Button(left3, text="Mini Window", command=self._toggle_mini).pack(
            side=tk.LEFT, padx=5
        )
        ttk.Button(left3, text="Hide Main", command=self._hide_main).pack(side=tk.LEFT, padx=5)
        self.btn_save = ttk.Button(left3, text="Save Settings", command=self._save_settings_clicked)
        self.btn_save.pack(side=tk.LEFT, padx=5)

        # ---------- Status grid ----------
        frm_grid = ttk.LabelFrame(self, text="Status", padding=8)
        frm_grid.pack(side=tk.TOP, fill=tk.X, padx=8, pady=6)

        self.fields = [
            "UTC",
            "Date",
            "Talker",
            "Fix",
            "Sats Used",
            "In View",
            "PDOP",
            "HDOP",
            "VDOP",
            "Altitude",
            "Heading",
            "Checksums",
        ]
        self.var_map = {}
        for i, name in enumerate(self.fields):
            ttk.Label(frm_grid, text=name + ":").grid(
                row=i // 3, column=(i % 3) * 2, sticky="e", padx=(0, 6), pady=3
            )
            var = tk.StringVar(value="—")
            ttk.Label(frm_grid, textvariable=var, width=18).grid(
                row=i // 3, column=(i % 3) * 2 + 1, sticky="w", padx=(0, 12), pady=3
            )
            self.var_map[name] = var

        # ---------- Heading canvas ----------
        frm_heading = ttk.LabelFrame(self, text="Heading (filtered + offset)", padding=8)
        frm_heading.pack(side=tk.TOP, fill=tk.X, padx=8, pady=6)

        self.heading_canvas = tk.Canvas(frm_heading, height=160)
        self.heading_canvas.pack(fill=tk.BOTH, expand=True)
        self.heading_text_id = self.heading_canvas.create_text(
            10, 70, anchor="w", font=("Segoe UI", 80, "bold"), text="--.-°T"
        )
        self.heading_sub_id = self.heading_canvas.create_text(
            12,
            125,
            anchor="w",
            font=("Segoe UI", 14),
            text="raw: --.-°T    FIR: 15    offset: 0.0°",
        )

        # ---------- NMEA Log ----------
        frm_log = ttk.LabelFrame(self, text="NMEA Log", padding=8)
        frm_log.pack(side=tk.TOP, fill=tk.BOTH, expand=True, padx=8, pady=(0, 8))

        self.txt_log = tk.Text(frm_log, height=10, wrap="none")
        self.txt_log.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        yscroll = ttk.Scrollbar(frm_log, orient="vertical", command=self.txt_log.yview)
        yscroll.pack(side=tk.RIGHT, fill=tk.Y)
        self.txt_log.configure(yscrollcommand=yscroll.set)
        self.txt_log.tag_configure("bad", foreground="orange red")
        self.txt_log.tag_configure("good", foreground="black")

    # ---------- Settings appl. ----------
    def _apply_settings_to_widgets(self):
        # Baud
        try:
            self.cmb_baud.set(str(int(self.settings.get("baud", DEFAULTS["baud"]))))
        except Exception:
            self.cmb_baud.set(str(DEFAULTS["baud"]))
        # FIR window
        try:
            self.spin_win.delete(0, tk.END)
            self.spin_win.insert(0, str(int(self.settings.get("fir_window", DEFAULTS["fir_window"]))))
        except Exception:
            self.spin_win.delete(0, tk.END)
            self.spin_win.insert(0, str(DEFAULTS["fir_window"]))
        # Offset
        try:
            self.spin_off.delete(0, tk.END)
            self.spin_off.insert(
                0, f'{float(self.settings.get("heading_offset_deg", DEFAULTS["heading_offset_deg"])):.1f}'
            )
        except Exception:
            self.spin_off.delete(0, tk.END)
            self.spin_off.insert(0, f'{DEFAULTS["heading_offset_deg"]:.1f}')
        # Port (must exist to select)
        self._populate_ports()
        port = self.settings.get("port", "")
        if port and port in self.cmb_port["values"]:
            self.cmb_port.set(port)

    def _populate_ports(self):
        ports = [p.device for p in list_ports.comports()]
        self.cmb_port["values"] = ports
        # Preserve selection if still present; else default
        cur = self.cmb_port.get()
        if cur in ports:
            self.cmb_port.set(cur)
            return
        # Prefer saved port, else COM8, else first
        saved = self.settings.get("port", "")
        if saved in ports:
            self.cmb_port.set(saved)
        elif "COM8" in ports:
            self.cmb_port.set("COM8")
        elif ports:
            self.cmb_port.set(ports[0])

    # ---------- Connect / Disconnect ----------
    def _toggle_connection(self):
        if self.reader and self.reader.is_alive():
            self._disconnect()
        else:
            self._connect()

    def _connect(self):
        port = self.cmb_port.get()
        try:
            baud = int(self.cmb_baud.get())
        except ValueError:
            messagebox.showwarning("Baud rate", "Please choose a numeric baud rate.")
            return
        if not port:
            messagebox.showwarning("No Port", "Please select a COM port.")
            return
        # Apply user settings to reader
        fir_n = int(self.spin_win.get() or DEFAULTS["fir_window"])
        offset = float(self.spin_off.get() or DEFAULTS["heading_offset_deg"])
        self.stop_event.clear()
        self.reader = SerialReader(
            port,
            baud,
            self.out_queue,
            self.log_queue,
            self.stop_event,
            fir_window=fir_n,
            heading_offset_deg=offset,
        )
        self.reader.start()
        self.lbl_status.configure(text=f"Connected to {port} @ {baud}", foreground="green")
        self.btn_connect.configure(text="Disconnect")

    def _disconnect(self):
        self.stop_event.set()
        self.lbl_status.configure(text="Disconnecting...", foreground="orange")
        self.after(300, self._finish_disconnect)

    def _finish_disconnect(self):
        self.reader = None
        self.lbl_status.configure(text="Disconnected", foreground="red")
        self.btn_connect.configure(text="Connect")

    # ---------- Top controls handlers ----------
    def _apply_window(self):
        try:
            n = int(float(self.spin_win.get()))
            if self.reader and self.reader.is_alive():
                self.reader.set_fir_window(n)
            # Update subtitle immediately
            self._update_heading_canvas(None, None, n, None)
        except ValueError:
            pass

    def _apply_offset(self):
        try:
            off = float(self.spin_off.get())
            if self.reader and self.reader.is_alive():
                self.reader.set_heading_offset(off)
            # Update subtitle immediately
            self._update_heading_canvas(None, None, None, off)
        except ValueError:
            pass

    def _save_settings_clicked(self):
        s = {
            "port": self.cmb_port.get(),
            "baud": int(self.cmb_baud.get()) if self.cmb_baud.get() else DEFAULTS["baud"],
            "fir_window": int(float(self.spin_win.get()) if self.spin_win.get() else DEFAULTS["fir_window"]),
            "heading_offset_deg": float(
                self.spin_off.get() if self.spin_off.get() else DEFAULTS["heading_offset_deg"]
            ),
        }
        ok, err = save_settings(s)
        if ok:
            messagebox.showinfo("Settings", f"Saved to {SETTINGS_FILE}")
            self.settings.update(s)
        else:
            messagebox.showerror("Settings", f"Failed to save settings:\n{err}")

    # ---------- Mini window ----------
    def _toggle_mini(self):
        # Close if already open
        if self._mini_win and self._mini_win.winfo_exists():
            self._mini_win.destroy()
            self._mini_win = None
            self._mini_heading = None
            self._mini_status = None
            return

        # Create a very simple mini window
        w = tk.Toplevel(self)
        w.title("Heading")
        w.geometry("460x200")
        w.attributes("-topmost", True)  # always on top
        w.protocol("WM_DELETE_WINDOW", lambda: (w.destroy(), setattr(self, "_mini_win", None)))

        self._mini_heading = tk.StringVar(value="--.-°T")
        self._mini_status = tk.StringVar(value="Fix: --   Used: ? / InView: ?   HDOP: ?")

        lbl = ttk.Label(w, textvariable=self._mini_heading, font=("Segoe UI", 64, "bold"))
        lbl.pack(fill="x", padx=10, pady=(10, 0))

        ttk.Label(w, textvariable=self._mini_status).pack(fill="x", padx=12, pady=(8, 12))

        self._mini_win = w

    def _hide_main(self):
        self.withdraw()  # hide main window

    def _apply_mini_on_top(self):
        if self._mini_win and self._mini_win.winfo_exists():
            self._mini_win.attributes("-topmost", True)

    # ---------- Update loop ----------
    def _poll_queues(self):
        # Update status fields and heading canvas
        try:
            while True:
                d = self.out_queue.get_nowait()
                for k, v in d.items():
                    if k in self.var_map:
                        self.var_map[k].set(v)
                raw_h = self.var_map["Heading"].get()
                raw_val = None
                if raw_h.endswith("°T"):
                    try:
                        raw_val = float(raw_h[:-2])
                    except Exception:
                        raw_val = None
                filt = d.get("_filtered_heading", None)
                win = int(float(self.spin_win.get() or DEFAULTS["fir_window"]))
                off = float(self.spin_off.get() or DEFAULTS["heading_offset_deg"])
                self._update_heading_canvas(filt, raw_val, win, off)

                # Feed mini window if present
                if self._mini_win and self._mini_win.winfo_exists():
                    if self._mini_heading:
                        self._mini_heading.set("--.-°T" if filt is None else f"{norm360(filt):0.1f}°T")
                    if self._mini_status:
                        fix_text = self.var_map["Fix"].get()
                        used = self.var_map["Sats Used"].get()
                        inview = self.var_map["In View"].get()
                        hdop = self.var_map["HDOP"].get()
                        self._mini_status.set(
                            f"Fix: {fix_text}   Used: {used} / InView: {inview}   HDOP: {hdop}"
                        )
        except queue.Empty:
            pass

        # Append log lines
        try:
            while True:
                line = self.log_queue.get_nowait()
                is_good = nmea_checksum_ok(line) if line.startswith("$") else True
                self.txt_log.insert(tk.END, line + "\n", "good" if is_good else "bad")
                self.txt_log.see(tk.END)
        except queue.Empty:
            pass

        self.after(100, self._poll_queues)

    def _update_heading_canvas(self, filt_deg, raw_deg, window_n, offset_deg):
        # Use current text if None provided to avoid flicker
        current_sub = self.heading_canvas.itemcget(self.heading_sub_id, "text")
        # Main text
        main_text = "--.-°T" if filt_deg is None else f"{norm360(filt_deg):0.1f}°T"
        self.heading_canvas.coords(self.heading_text_id, 10, 65)
        self.heading_canvas.itemconfig(self.heading_text_id, text=main_text)
        # Subtext
        if window_n is None:
            try:
                parts = current_sub.split("FIR:")[1].split()[0]
                window_n = int(parts)
            except Exception:
                window_n = int(float(self.spin_win.get() or DEFAULTS["fir_window"]))
        if offset_deg is None:
            try:
                parts = current_sub.split("offset:")[1].split("°")[0].strip()
                offset_deg = float(parts)
            except Exception:
                offset_deg = float(self.spin_off.get() or DEFAULTS["heading_offset_deg"])

        raw_text = "--.-°T" if raw_deg is None else f"{norm360(raw_deg):0.1f}°T"
        sub_text = f"raw: {raw_text}    FIR: {int(window_n)}    offset: {offset_deg:.1f}°"
        self.heading_canvas.coords(self.heading_sub_id, 14, 122)
        self.heading_canvas.itemconfig(self.heading_sub_id, text=sub_text)

    def _on_close(self):
        if self.reader and self.reader.is_alive():
            self.stop_event.set()
            time.sleep(0.2)
        self.destroy()


if __name__ == "__main__":
    app = NMEAGui()
    app.mainloop()
