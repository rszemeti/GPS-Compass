import serial, time, sys
from datetime import datetime

PORT = "COM8"   # your Brainboxes COM port
BAUD = 38400    # 8N1

# --- Helpers ---
def nmea_checksum_ok(sentence: str) -> bool:
    # sentence like $GPGGA,...*hh  (may include \r\n)
    if "*" not in sentence:
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
    # returns (talker, type, fields list)
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
    except:
        return None

def safe_float(s):
    try:
        return float(s)
    except:
        return None

# --- Live state we keep updating ---
state = {
    "utc": None,             # "HH:MM:SS"
    "date": None,            # "YYYY-MM-DD"
    "fix": 0,                # 0=none, 1=GPS fix, 2=DGPS, ...
    "sats_used": None,       # from GSA/GGA
    "sats_in_view": None,    # from GSV
    "pdop": None, "hdop": None, "vdop": None,
    "alt_m": None,           # altitude meters
    "heading_T": None,       # HDT true heading
    "last_talker": None,
    "ok_count": 0,
    "bad_count": 0,
    "last_update": time.time(),
}

FIX_QUAL = {
    0: "No fix",
    1: "GPS",
    2: "DGPS",
    4: "RTK Fixed",
    5: "RTK Float",
    6: "Dead reckoning",
}

GSA_FIX = {
    1: "No fix",
    2: "2D",
    3: "3D",
}

def render_status():
    # Build a compact status line
    utc = state["utc"] or "--:--:--"
    date = state["date"] or "--------"
    fix_desc = FIX_QUAL.get(state["fix"], str(state["fix"])) if state["fix"] is not None else "?"
    used = state["sats_used"] if state["sats_used"] is not None else "?"
    view = state["sats_in_view"] if state["sats_in_view"] is not None else "?"
    hdop = state["hdop"] if state["hdop"] is not None else "?"
    pdop = state["pdop"] if state["pdop"] is not None else "?"
    vdop = state["vdop"] if state["vdop"] is not None else "?"
    alt = f'{state["alt_m"]:.1f} m' if state["alt_m"] is not None else "?"
    hdg = f'{state["heading_T"]:.1f}°T' if state["heading_T"] is not None else "?"
    okb = f'OK:{state["ok_count"]} Bad:{state["bad_count"]}'
    talker = state["last_talker"] or "--"

    line1 = f'UTC {utc}  Date {date}  Talker {talker}  {okb}'
    line2 = f'Fix {fix_desc}  Used {used}  InView {view}  PDOP {pdop}  HDOP {hdop}  VDOP {vdop}'
    line3 = f'Alt {alt}  Heading {hdg}'
    print("\r" + " " * 120, end="")  # clear previous
    print(f"\r{line1}\n{line2}\n{line3}\n", end="")

def handle_sentence(sentence: str):
    if not sentence.startswith("$"):
        return
    if nmea_checksum_ok(sentence):
        state["ok_count"] += 1
    else:
        state["bad_count"] += 1
        # Still try to parse; some devices omit checksum occasionally.

    talker, typ, f = split_fields(sentence)
    state["last_talker"] = talker

    if typ == "GGA" and len(f) >= 14:
        # $xxGGA,hhmmss,lat,N,lon,E,fix,nsat,hdop,alt,M,geoid,M,age,station*CS
        state["fix"] = safe_int(f[5]) or 0
        nsat = safe_int(f[6])
        if nsat is not None:
            state["sats_used"] = nsat
        state["hdop"] = safe_float(f[7]) if f[7] else state["hdop"]
        state["alt_m"] = safe_float(f[8]) if f[8] else state["alt_m"]
        # time
        if f[0] and len(f[0]) >= 6:
            hh, mm, ss = f[0][0:2], f[0][2:4], f[0][4:6]
            state["utc"] = f"{hh}:{mm}:{ss}"

    elif typ == "GSA" and len(f) >= 17:
        # $xxGSA,Mode(M/A),Fix(1/2/3),sat1..sat12,PDOP,HDOP,VDOP*CS
        fix_mode = safe_int(f[1])  # 1,2,3
        # Satellites used count = non-empty among f[2:14]
        used = sum(1 for s in f[2:14] if s.strip())
        if used > 0:
            state["sats_used"] = used
        pdop = safe_float(f[14]); hdop = safe_float(f[15]); vdop = safe_float(f[16])
        if pdop is not None: state["pdop"] = pdop
        if hdop is not None: state["hdop"] = hdop
        if vdop is not None: state["vdop"] = vdop
        # If GGA fix unknown, infer "no fix" vs 2D/3D for display purposes
        if state["fix"] == 0 and fix_mode in GSA_FIX:
            # leave as 0 (No fix) unless you want to map 2/3 to nonzero

            pass

    elif typ == "GSV" and len(f) >= 4:
        # $xxGSV,total_msgs,msg_num,sv_in_view, ... *CS
        siv = safe_int(f[2])
        if siv is not None:
            state["sats_in_view"] = siv

    elif typ == "ZDA" and len(f) >= 6:
        # $xxZDA,hhmmss,DD,MM,YYYY,LTZH,LTZN*CS
        if f[0] and len(f[0]) >= 6:
            hh, mm, ss = f[0][0:2], f[0][2:4], f[0][4:6]
            state["utc"] = f"{hh}:{mm}:{ss}"
        if f[3] and f[2] and f[1]:
            year = safe_int(f[3]); mon = safe_int(f[2]); day = safe_int(f[1])
            if year and mon and day:
                state["date"] = f"{year:04d}-{mon:02d}-{day:02d}"

    elif typ == "HDT" and len(f) >= 2:
        # $xxHDT,heading,T*CS
        hdg = safe_float(f[0])
        if hdg is not None:
            state["heading_T"] = hdg

def main():
    try:
        ser = serial.Serial(PORT, BAUD, timeout=1, bytesize=serial.EIGHTBITS,
                            parity=serial.PARITY_NONE, stopbits=serial.STOPBITS_ONE)
    except Exception as e:
        print(f"Failed to open {PORT}: {e}")
        sys.exit(1)

    print(f"Listening on {PORT} at {BAUD} 8N1...\n(Press Ctrl+C to stop)\n")
    buf = bytearray()
    last_render = 0.0

    try:
        while True:
            chunk = ser.read(4096)
            if chunk:
                buf.extend(chunk)
                # split by CR/LF
                while True:
                    i_cr = buf.find(b"\r")
                    i_lf = buf.find(b"\n")
                    idx = min([x for x in (i_cr, i_lf) if x != -1], default=-1)
                    if idx == -1:
                        break
                    line = bytes(buf[:idx]).decode(errors="replace").strip()
                    # drop \r\n pair
                    drop = 2 if idx + 1 < len(buf) and buf[idx:idx+2] in (b"\r\n", b"\n\r") else 1
                    del buf[:idx + drop]
                    if line:
                        handle_sentence(line)

            now = time.time()
            if now - last_render >= 1.0:
                render_status()
                last_render = now
    except KeyboardInterrupt:
        print("\nStopping.")
    finally:
        try: ser.close()
        except: pass

if __name__ == "__main__":
    main()
