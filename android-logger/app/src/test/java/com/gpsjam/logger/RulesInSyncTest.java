package com.gpsjam.logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The map page (index.html, classify()) repeats JamDetector's rules in JavaScript.
 * This fails when a threshold is changed on one side only.
 */
public class RulesInSyncTest {

    private static String html;

    @BeforeClass public static void readMapPage() throws Exception {
        html = new String(Files.readAllBytes(new File("../../index.html").toPath()), StandardCharsets.UTF_8);
    }

    /** 6.0 -> "6", 1.5 -> "1.5", the way the JavaScript writes numbers. */
    private static String js(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private static double jsDefault(String key) {
        Matcher m = Pattern.compile("var DEFAULTS = \\{[^}]*\\b" + key + ": ([0-9.]+)").matcher(html);
        assertTrue("DEFAULTS." + key + " not found in index.html", m.find());
        return Double.parseDouble(m.group(1));
    }

    private static double sliderDefault(String id) {
        Matcher m = Pattern.compile("id=\"" + id + "\"[^>]*value=\"([0-9.]+)\"").matcher(html);
        assertTrue("slider " + id + " not found in index.html", m.find());
        return Double.parseDouble(m.group(1));
    }

    private static void assertCode(String snippet) {
        assertTrue("index.html classify() should contain: " + snippet, html.contains(snippet));
    }

    @Test public void thresholdsMatch() {
        String[][] pairs = {
            {"dropWarn", js(JamDetector.DROP_WARN_DB)}, {"dropJam", js(JamDetector.DROP_JAM_DB)},
            {"agcWarn", js(JamDetector.AGC_DROP_WARN_DB)}, {"agcJam", js(JamDetector.AGC_DROP_JAM_DB)},
        };
        for (String[] p : pairs) {
            double java = Double.parseDouble(p[1]);
            assertEquals("DEFAULTS." + p[0], java, jsDefault(p[0]), 0);
            assertEquals("slider " + p[0], java, sliderDefault(p[0]), 0);
        }
    }

    @Test public void baselineMatches() {
        assertCode("Math.max(" + js(JamDetector.MIN_BASELINE_CN0) + ", percentile(tops, 0.75) || "
                + js(JamDetector.DEFAULT_BASELINE_CN0) + ")");
        assertCode("percentile(agcs, 0.75)");
    }

    @Test public void noAgcAndSpoofingRulesMatch() {
        assertCode("x.elev >= " + js(JamDetector.HIGH_ELEV_DEG));
        assertCode("high.length >= 2 && (base - mean(high)) >= s.dropJam");
        assertCode("e.nSats >= " + JamDetector.UNUSUAL_MIN_SATS + " && e.top4 >= " + js(JamDetector.UNUSUAL_MIN_TOP4)
                + " && std(e.cn0s) < " + js(JamDetector.UNUSUAL_MAX_STD));
    }
}
