# GPS jamming spotter (Android logger + map)

**Map:** https://thefakeblue.github.io/gps-jamming-spoter/  
**App:** [GpsJamLogger.apk](GpsJamLogger.apk) (tap, then the download button)

Two pieces:

- **GpsJamLogger.apk**: an Android app that records, every second, how strong each
  satellite signal is (C/N0), the phone's AGC "noise gauge" when the phone reports it,
  and where you are. It shows a live verdict (Normal / Blockage / Interference / Jamming).
- **jamming-map.html**: open it in a browser on your computer, load the app's CSV, and it
  colours your route by verdict, lists suspected jamming spots, and graphs signal over time.

## Install the app (Android 7 or newer)

1. Get `GpsJamLogger.apk` onto the phone (download it on the phone, or email/Drive it to yourself).
2. Tap the file. Android asks to allow installs from that app (Chrome, Files, Gmail…): allow it.
3. Play Protect may say the app is from an unknown developer. Tap **More details → Install anyway**.
   (It's unknown because it was built here and not published on the Play Store.)
4. Open **GPS Jam Logger**, tap **Start logging**, and allow *precise* location and notifications.

## Look at your data inside the app

- **View readings**: a summary of the log, every satellite heard in the latest second (bar = signal
  strength, ✓ = used for your position), and a second-by-second list with the verdict, signal, satellite
  count and AGC. It refreshes by itself while you're recording.
- **Raw data (JSON)**: the newest readings as JSON, one block per satellite per second, using the same
  field names as the GPSI app (Latitude, Longitude, Altitude, Device, Constellation, Vehicle,
  SignalToNoise, SatCount, TimeStamp) plus Band, AGC, Elevation, Azimuth, UsedInFix and Status. Copy button included.
- **Show on street map**: the same map as the website, with streets, opened right in the app with your
  log already loaded (needs internet for the streets). Use **Choose log** to pick an older recording.

## Collect data

- Start outdoors with a clear sky for a minute so the app learns what "normal" is for your phone.
- Logging keeps running with the screen off (there's a notification with a Stop button).
- Better data on some phones: Settings → About phone → tap *Build number* 7 times, then
  Developer options → turn on **Force full GNSS measurements**.
- When done, tap **Stop logging**, then **Save log to Downloads** (lands in `Download/GpsJamLogger/`)
  or **Share log** to email it to yourself.

## Look at the map

Open the map link above (or `index.html`) in any browser, including on your phone,
click **Open log file…** and choose the CSV. Click **Try demo data** to see what a jammer looks like.
You can load several logs at once. Use the Sensitivity sliders if it flags too much or too little.

## Airplane GPS check (no equipment needed)

Airplanes broadcast (ADS-B) how sure they are of their own GPS position. When many planes in one
area report poor GPS at once, something there is usually jamming it. The map shows this as squares:
green (fine), yellow (some trouble), red (jamming likely), grey (too few planes to tell).

- **Whole world, last 24 h**: shown when the map opens. A GitHub Action (`.github/workflows/adsb.yml`)
  sweeps free data from adsb.lol, airplanes.live and adsb.fi about every 30 minutes. The free services only
  answer for 250 nm circles, so it asks for ~830 circles covering the world (each service at most once every
  2 seconds; empty ocean circles only now and then) and saves a summary on the `adsb-data` branch. GitHub may pause scheduled runs if the repo has
  no activity for 60 days; re-enable it under the Actions tab.
- **Live check here**: checks planes around wherever the map is centred, right now. This works in the
  Android app's map. Most web browsers block it on the website because the free services don't allow
  web pages to read them directly.
- Data is free for personal, non-commercial use: adsb.lol (ODbL), airplanes.live, adsb.fi.

## How jamming is told apart from blockage

- **Jamming**: every satellite gets weaker at the same time *and* the AGC drops (the receiver turns
  its gain down because extra radio power arrived).
- **Blockage** (trees, buildings, roofs): signals get weaker but AGC stays about the same, and
  satellites high overhead usually stay strong.
- Not every phone reports AGC. Without it, the app falls back to "are even the high satellites weak?",
  which is less certain.

## CSV columns

`utc_ms, local_time, lat, lon, alt_m, acc_m, speed_mps, loc_source (gps / network / gps_stale), loc_age_s,
constellation, svid, band, freq_mhz, cn0_dbhz, agc_db, elev_deg, azim_deg, used_in_fix,
epoch_sats, epoch_top4_cn0, epoch_status`: one row per satellite per second. A second with no satellites
still gets one row (blank satellite columns) so outages show up on the map.

## Rebuilding the app

- **Android Studio**: File → Open → the `android-logger` folder, then Run.
- **Command line (Linux)**: see the top of `android-logger/build-apk.sh`, then run it.
