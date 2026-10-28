package com.fermabets.model;

import com.fermabets.model.FermabetsModel.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class FermabetsModelParityTest {

    static List<Game> sample() {
        List<Game> games = new ArrayList<>();
        String[] opp = {"NYK", "BOS", "MIA", "BOS", "CHI", "NYK", "BOS", "ATL", "BOS", "ORL", "NYK", "BOS"};
        boolean[] home = {true, true, false, true, true, false, true, true, false, true, true, true};
        for (int i = 0; i < 12; i++) {
            double min = 24 + (i % 8);
            double fga = 8 + (i % 7);
            double fta = i % 5;
            double pa3 = 2 + (i % 5);
            double usg = 18 + (i % 9);
            double pace = 96 + (i % 8);
            double pts = 6 + 0.35 * min + 0.4 * fga + (i % 3);
            games.add(new Game(pts, min, fga, fta, pa3, usg, pace, home[i], opp[i]));
        }
        return games;
    }

    static Inputs sampleInputs() {
        return new Inputs("pts", 20.5, Estimate.range(26, 32), Estimate.range(10, 16), Estimate.range(1, 5),
                Estimate.range(20, 28), Estimate.range(96, 104), Estimate.range(3, 8), true, "BOS");
    }

    @Test
    void filtersAndSimilarityBlendAndStakeMatchPythonSnapshot() {
        Result r = FermabetsModel.evaluate(sample(), sampleInputs());
        assertEquals(0.819665, r.prob(), 0.001);
        assertEquals(0.682560, r.pFilters(), 0.001);
        assertEquals(0.987238, r.pSimilarity(), 0.001);
        Stake s = FermabetsModel.stake(r.prob(), 1.80, 100);
        assertEquals("BET", s.status());
        assertTrue(s.stake() > 0);
        assertTrue(s.stake() <= 5.0);
        assertEquals(0.05, FermabetsModel.MAX_STAKE_PCT, 1e-9);
        assertEquals(0.55, FermabetsModel.BLEND_A, 1e-9);
    }

    @Test
    void pythonPropsModelAgreesWhenAvailable() throws Exception {
        Path script = Path.of("src/test/resources/parity_case.py");
        if (!Files.exists(script)) {
            return;
        }
        Path py = Path.of("ingest/.venv/Scripts/python.exe");
        String exe = Files.exists(py) ? py.toString() : "python";
        Process p = new ProcessBuilder(exe, script.toString()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        int code = p.waitFor();
        if (code != 0 || !out.contains("prob=")) {
            return;
        }
        double prob = Double.parseDouble(out.replaceAll("(?s).*prob=([0-9.]+).*", "$1"));
        Result r = FermabetsModel.evaluate(sample(), sampleInputs());
        assertEquals(prob, r.prob(), 0.001, () -> "python said " + out + " java " + String.format(Locale.US, "%.6f", r.prob()));
    }

    @Test
    void h2hGamesAreRemovedFromOtherFilters() {
        Result r = FermabetsModel.evaluate(sample(), sampleInputs());
        Factor h2h = r.factors().stream().filter(f -> f.name().equals("h2h")).findFirst().orElseThrow();
        assertEquals(5, h2h.n());
        Factor mins = r.factors().stream().filter(f -> f.name().equals("mins")).findFirst().orElseThrow();
        assertTrue(mins.n() < 12);
    }

    @Test
    void negativeEvIsNoBetAndCapIsFivePercent() {
        Stake no = FermabetsModel.stake(0.40, 1.80, 100);
        assertEquals("NO BET", no.status());
        assertEquals(0.0, no.stake());
        Stake cap = FermabetsModel.stake(0.90, 2.00, 100);
        assertTrue(cap.capped());
        assertEquals(5.0, cap.stake(), 1e-9);
    }
}
