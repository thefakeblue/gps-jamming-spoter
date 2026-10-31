package com.gpsjam.logger;

import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Replays real recorded logs through JamDetector, second by second, and checks it reaches the
 * same verdicts the app wrote live (the epoch_status column).
 *
 * Put CSV logs in app/src/test/resources/private/. That folder is git-ignored because logs show
 * where you've been. With no logs there, this test is skipped.
 *
 * The match can't be perfect: the CSV rounds C/N0 to 0.1 dB, and on Android 8-12 each satellite
 * has its own AGC value, while the app used one per constellation. So a few seconds sitting right
 * on a threshold may flip.
 */
public class JamDetectorReplayTest {

    private static final File DIR = new File("src/test/resources/private");
    private static final double MIN_MATCH = 0.99;

    @Test public void replayedLogsGiveTheVerdictsRecordedLive() throws Exception {
        File[] logs = DIR.listFiles((d, name) -> name.toLowerCase(Locale.US).endsWith(".csv"));
        Assume.assumeTrue("No logs in " + DIR.getAbsolutePath() + ", skipping replay", logs != null && logs.length > 0);
        Arrays.sort(logs);
        for (File log : logs) {
            LogReader.Summary s = LogReader.read(log, Integer.MAX_VALUE);
            JamDetector detector = new JamDetector();
            int total = 0, same = 0;
            Map<String, Integer> diffs = new TreeMap<>();
            for (LogReader.Epoch e : s.recent) {
                List<JamDetector.Sat> sats = new ArrayList<>();
                for (LogReader.Sat sat : e.satList) if (sat.cn0 > 0) sats.add(new JamDetector.Sat(sat.cn0, sat.elev));
                String replayed = detector.evaluate(sats, e.agc).status;
                if (e.status.isEmpty()) continue;  // older logs or the map's demo data have no live verdict
                total++;
                if (replayed.equals(e.status)) same++;
                else diffs.merge(e.status + " -> " + replayed, 1, Integer::sum);
            }
            double match = total == 0 ? 1 : (double) same / total;
            System.out.printf(Locale.US, "%s: %d seconds, modes %s, verdicts %s, replay matches %.2f%%%s%n",
                    log.getName(), s.seconds, s.modeCounts, s.statusCounts, 100 * match,
                    diffs.isEmpty() ? "" : ", differences (live -> replay) " + diffs);
            assertTrue(log.getName() + ": only " + same + " of " + total + " seconds match " + diffs, match >= MIN_MATCH);
        }
    }
}
