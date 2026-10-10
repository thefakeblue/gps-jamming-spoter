#!/usr/bin/env python3
"""Fetch free ADS-B data around one spot and turn it into a GPS-quality map.

Run by .github/workflows/adsb.yml every ~10 minutes. Airplanes broadcast how
sure they are of their own GPS position (NACp and NIC). When many planes in
one area suddenly report poor GPS, something is usually jamming GPS there.

Writes into the folder given as the first argument:
  state.json   per grid cell, the planes seen there in the last 24 hours
  latest.json  every plane from this run, with its GPS-quality verdict
  grid.json    per grid cell, how many planes were seen and how many had poor GPS

The map page reads latest.json and grid.json. Rules must match planeQuality()
in index.html.
"""
import json
import os
import sys
import time
import urllib.request

LAT, LON, RADIUS_NM = 35.08, -106.65, 250   # Albuquerque, 250 nm covers New Mexico
CELL = 0.5                                   # grid cell size in degrees
KEEP_S = 24 * 3600

SOURCES = [
    ("adsb.lol", "https://api.adsb.lol/v2/point/{lat}/{lon}/{r}"),
    ("airplanes.live", "https://api.airplanes.live/v2/point/{lat}/{lon}/{r}"),
    ("adsb.fi", "https://opendata.adsb.fi/api/v3/lat/{lat}/lon/{lon}/dist/{r}"),
]


def fetch():
    errors = []
    for name, url in SOURCES:
        url = url.format(lat=LAT, lon=LON, r=RADIUS_NM)
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "gps-jamming-spoter (github.com/thefakeblue/gps-jamming-spoter)"})
            with urllib.request.urlopen(req, timeout=30) as r:
                data = json.load(r)
            planes = data.get("ac") or data.get("aircraft")
            if planes is None:
                raise ValueError("no aircraft list in reply")
            return name, planes
        except Exception as e:  # try the next free source
            errors.append("%s: %s" % (name, e))
    raise SystemExit("All ADS-B sources failed: " + "; ".join(errors))


def quality(a):
    """'good', 'poor' or 'lost' GPS, or None when the plane can't tell us."""
    if a.get("alt_baro") == "ground":
        return None                       # parked planes often report junk
    if a.get("type") not in (None, "adsb_icao", "adsr_icao"):
        return None                       # positions worked out by ground stations, not the plane's GPS
    if a.get("gpsOkBefore") is not None:
        return "lost"                     # plane stopped sending GPS positions recently
    nacp, nic, ver = a.get("nac_p"), a.get("nic"), a.get("version")
    if nacp is None or nic is None or not ver:
        return None                       # old transponders (version 0) don't report quality properly
    return "poor" if nacp < 8 or nic < 7 else "good"


def position(a):
    if a.get("gpsOkBefore") is not None and a.get("gpsOkLat") is not None:
        return a["gpsOkLat"], a["gpsOkLon"]   # where its GPS was last fine
    if a.get("lat") is not None:
        return a["lat"], a["lon"]
    lp = a.get("lastPosition") or {}
    if lp.get("lat") is not None:
        return lp["lat"], lp["lon"]
    return None


def cell_key(lat, lon):
    return "%.2f,%.2f" % (CELL * (lat // CELL), CELL * (lon // CELL))


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "adsb-out"
    os.makedirs(out, exist_ok=True)
    now = int(time.time())
    try:
        with open(os.path.join(out, "state.json")) as f:
            state = json.load(f)
    except Exception:
        state = {}

    source, planes = fetch()
    latest = []
    for a in planes:
        q, pos = quality(a), position(a)
        if not q or not pos:
            continue
        hexid = a.get("hex", "?")
        latest.append({
            "hex": hexid, "flight": (a.get("flight") or "").strip(), "r": a.get("r"), "t": a.get("t"),
            "lat": round(pos[0], 4), "lon": round(pos[1], 4), "alt": a.get("alt_baro"),
            "nacp": a.get("nac_p"), "nic": a.get("nic"), "q": q,
        })
        # per cell, per plane: [last seen, good sightings, poor sightings]
        rec = state.setdefault(cell_key(*pos), {}).setdefault(hexid, [now, 0, 0])
        rec[0] = now
        rec[1 if q == "good" else 2] += 1

    grid = []
    for key in list(state):
        cell = {h: r for h, r in state[key].items() if now - r[0] < KEEP_S}
        if not cell:
            del state[key]
            continue
        state[key] = cell
        bad = sum(1 for r in cell.values() if r[2] >= r[1])   # poor at least half the time
        lat, lon = map(float, key.split(","))
        grid.append({"lat": lat, "lon": lon, "size": CELL, "planes": len(cell), "bad": bad})

    meta = {"time": now, "source": source, "center": [LAT, LON], "radius_nm": RADIUS_NM}
    with open(os.path.join(out, "state.json"), "w") as f:
        json.dump(state, f, separators=(",", ":"))
    with open(os.path.join(out, "latest.json"), "w") as f:
        json.dump(dict(meta, planes=latest), f, separators=(",", ":"))
    with open(os.path.join(out, "grid.json"), "w") as f:
        json.dump(dict(meta, hours=24, cells=grid), f, separators=(",", ":"))
    print("%s: %d planes, %d with usable GPS quality, %d poor/lost, %d cells" % (
        source, len(planes), len(latest), sum(1 for p in latest if p["q"] != "good"), len(grid)))


if __name__ == "__main__":
    main()
