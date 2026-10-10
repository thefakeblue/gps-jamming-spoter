#!/usr/bin/env python3
"""Sweep free ADS-B data over the whole world and turn it into a GPS-quality map.

Run by .github/workflows/adsb.yml. Airplanes broadcast how sure they are of
their own GPS position (NACp and NIC). When many planes in one area report
poor GPS, something is usually jamming GPS there.

None of the free services offers "every plane on Earth" in one request, only
circles up to 250 nm wide, so the world is covered with ~830 overlapping
circles. Circles that had no planes for a day are only re-checked now and then.
Each service gets at most one request every 2 seconds.

Writes into the folder given as the first argument:
  state.json   per grid cell, the planes seen there in the last 24 hours
  latest.json  every plane from this run, with its GPS-quality verdict
  grid.json    per grid cell, how many planes were seen and how many had poor GPS

The map page reads latest.json and grid.json. Rules must match planeQuality()
in index.html.
"""
import json
import math
import os
import queue
import sys
import threading
import time
import urllib.request

RADIUS_NM = 250
CELL = 0.5                 # grid cell size in degrees
KEEP_S = 24 * 3600
GAP_S = 2.0                # seconds between requests to the same service
RECHECK_EMPTY = 12         # an empty circle is re-checked every 12th run
MAX_RUN_S = 25 * 60

SOURCES = [
    ("adsb.lol", "https://api.adsb.lol/v2/point/{lat}/{lon}/{r}"),
    ("airplanes.live", "https://api.airplanes.live/v2/point/{lat}/{lon}/{r}"),
    ("adsb.fi", "https://opendata.adsb.fi/api/v3/lat/{lat}/lon/{lon}/dist/{r}"),
]
UA = "gps-jamming-spoter (github.com/thefakeblue/gps-jamming-spoter)"


def circles():
    """Centres of 250 nm circles that together cover latitudes 56S to 74N."""
    r = RADIUS_NM / 60.0
    out, row, lat = [], 0, -56.0
    while lat <= 74:
        step = math.sqrt(3) * r / max(math.cos(math.radians(lat)), 0.2)
        lon = -180 + (step / 2 if row % 2 else 0)
        while lon < 180:
            out.append((round(lat, 2), round(lon, 2)))
            lon += step
        lat += 1.5 * r
        row += 1
    return out


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        data = json.load(r)
    planes = data.get("ac") if "ac" in data else data.get("aircraft")
    if planes is None:
        raise ValueError("no aircraft list in reply")
    return planes


def sweep(todo, deadline):
    """Ask the services for every circle in todo. Returns ({circle: planes}, {service: count})."""
    jobs = queue.Queue()
    for c in todo:
        jobs.put((c, ()))
    results, used, lock = {}, {}, threading.Lock()
    alive = [len(SOURCES)]

    def worker(name, url):
        fails = 0
        while time.time() < deadline:
            try:
                c, tried = jobs.get(timeout=5)
            except queue.Empty:
                if not pending():
                    return
                continue
            if name in tried and len(tried) < alive[0]:
                jobs.put((c, tried))     # let another service have it
                time.sleep(0.2)
                continue
            t0 = time.time()
            try:
                planes = get(url.format(lat=c[0], lon=c[1], r=RADIUS_NM))
                with lock:
                    results[c] = planes
                    used[name] = used.get(name, 0) + 1
                fails = 0
            except Exception as e:
                fails += 1
                if len(tried) + 1 < len(SOURCES):
                    jobs.put((c, tried + (name,)))
                else:
                    with lock:
                        results.setdefault(c, None)
                if fails >= 8:
                    print("giving up on %s: %s" % (name, e))
                    with lock:
                        alive[0] -= 1
                    return
                time.sleep(GAP_S * fails)
            time.sleep(max(0, GAP_S - (time.time() - t0)))

    def pending():
        return len(results) < len(todo)

    threads = [threading.Thread(target=worker, args=s, daemon=True) for s in SOURCES]
    for t in threads:
        t.start()
    while any(t.is_alive() for t in threads) and pending() and time.time() < deadline:
        time.sleep(1)
    return {c: p for c, p in results.items() if p is not None}, used


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
    return "%.1f,%.1f" % (CELL * (lat // CELL), CELL * (lon // CELL))


def load(path, default):
    try:
        with open(path) as f:
            return json.load(f)
    except Exception:
        return default


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "adsb-out"
    os.makedirs(out, exist_ok=True)
    now = int(time.time())
    state = load(os.path.join(out, "state.json"), {})
    if "cells" not in state:          # older single-area format
        state = {"cells": {}, "circles": {}, "run": 0}
    cells, seen_at = state["cells"], state["circles"]   # seen_at: circle -> last time it had planes
    run = state["run"] = state.get("run", 0) + 1

    all_circles = circles()
    todo = [c for i, c in enumerate(all_circles)
            if now - seen_at.get("%s,%s" % c, now) < KEEP_S or i % RECHECK_EMPTY == run % RECHECK_EMPTY]
    replies, used = sweep(todo, time.time() + MAX_RUN_S)

    planes = {}
    for c, lst in replies.items():
        if lst:
            seen_at["%s,%s" % c] = now
        else:
            seen_at.setdefault("%s,%s" % c, 0)
        for a in lst:
            planes[a.get("hex", "?")] = a        # circles overlap; keep each plane once

    latest = []
    for hexid, a in planes.items():
        q, pos = quality(a), position(a)
        if not q or not pos:
            continue
        latest.append([round(pos[0], 3), round(pos[1], 3), q, hexid, (a.get("flight") or "").strip(),
                       a.get("alt_baro"), a.get("nac_p"), a.get("nic"), a.get("t")])
        # per cell, per plane: [last seen, good sightings, poor sightings]
        rec = cells.setdefault(cell_key(*pos), {}).setdefault(hexid, [now, 0, 0])
        rec[0] = now
        rec[1 if q == "good" else 2] += 1

    grid = []
    for key in list(cells):
        cell = {h: r for h, r in cells[key].items() if now - r[0] < KEEP_S}
        if not cell:
            del cells[key]
            continue
        cells[key] = cell
        bad = sum(1 for r in cell.values() if r[2] >= r[1])   # poor at least half the time
        lat, lon = map(float, key.split(","))
        grid.append([lat, lon, len(cell), bad])

    meta = {"time": now, "source": ", ".join(sorted(used)), "area": "world", "circles": len(replies)}
    with open(os.path.join(out, "state.json"), "w") as f:
        json.dump(state, f, separators=(",", ":"))
    with open(os.path.join(out, "latest.json"), "w") as f:
        json.dump(dict(meta, fields=["lat", "lon", "q", "hex", "flight", "alt", "nacp", "nic", "t"], planes=latest), f, separators=(",", ":"))
    with open(os.path.join(out, "grid.json"), "w") as f:
        json.dump(dict(meta, hours=24, size=CELL, fields=["lat", "lon", "planes", "bad"], cells=grid), f, separators=(",", ":"))
    print("asked %d of %d circles, got %d (%s); %d planes, %d with usable GPS quality, %d poor/lost; %d cells" % (
        len(todo), len(all_circles), len(replies), used, len(planes), len(latest),
        sum(1 for p in latest if p[2] != "good"), len(grid)))


if __name__ == "__main__":
    main()
