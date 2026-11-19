package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.ac.starlink.ast.testsupport.ProbeData;

/**
 * Translation of Rust {@code tests/test_rate_oracle.rs}: rates of change from classes that
 * delegate to a component's rate, against C's {@code astRate}, from {@code oracle/rate.json}
 * ({@code tools/oracle_fixtures/rate_probe.c}): a RateMap asks its Mapping's rate at every point,
 * bad inputs included (ratemap.c Transform); a TranMap its forward component's, in the orientation
 * it records (tranmap.c Rate). A RateMap read from a dump whose recorded orientation ({@code Inv})
 * differs from its Mapping's Invert asks the rate in the recorded orientation. A SwitchMap asks the
 * rate of the route its forward selector, a SelectorMap of Box Regions, picks (switchmap.c Rate).
 */
class RateOracleTest {

    private static final double BAD = AstObject.AST__BAD;

    /** The output of a RateMap of {@code map} (output 1 with respect to input 1) at {@code at}. */
    private static double ratemapAt(Mapping map, double... at) {
        return new RateMap(map, 1, 1).tranN(1, at.length, at, true, 1)[0];
    }

    /** A SelectorMap over the 1-D intervals {@code bounds}, each {lower, upper}. */
    private static SelectorMap selector(double[]... bounds) {
        Region[] regions = new Region[bounds.length];
        for (int i = 0; i < bounds.length; i++) {
            regions[i] = new Box(new Frame(1), 1, new double[] {bounds[i][0]}, new double[] {bounds[i][1]}, null);
        }
        return new SelectorMap(regions, BAD);
    }

    private static WinMap route(double lo, double hi, double olo, double ohi) {
        return new WinMap(1, new double[] {lo}, new double[] {hi}, new double[] {olo}, new double[] {ohi});
    }

    /**
     * The probe's SwitchMap: routes from [0,10] to [0,100] and from [20,30] to [200,300], a
     * forward selector over the input intervals and, if {@code inverse}, an inverse selector over
     * the output intervals, given inverted as C's constructor takes it.
     */
    private static SwitchMap switchmap(boolean inverse) {
        SelectorMap ismap = null;
        if (inverse) {
            ismap = selector(new double[] {0.0, 100.0}, new double[] {200.0, 300.0});
            ismap.invert();
        }
        return new SwitchMap(selector(new double[] {0.0, 10.0}, new double[] {20.0, 30.0}), ismap, new Mapping[] {
            route(0.0, 10.0, 0.0, 100.0), route(20.0, 30.0, 200.0, 300.0)
        });
    }

    /** Java's rate for a case. */
    private static double rate(String name) {
        switch (name) {
            case "ratemap_zoommap_bad_input":
                return ratemapAt(new ZoomMap(1, 3.0), BAD);
            case "ratemap_unitmap_bad_other_axis":
                return ratemapAt(new UnitMap(2), 1.0, BAD);
            case "tranmap_forward":
            case "tranmap_inverted": {
                TranMap tm = new TranMap(new ZoomMap(1, 2.0), new ZoomMap(1, 3.0));
                if ("tranmap_inverted".equals(name)) {
                    tm.invert();
                }
                return tm.rate(new double[] {1.0}, 1, 1);
            }
            case "ratemap_recorded_orientation": {
                ZoomMap zm = new ZoomMap(1, 2.0);
                zm.invert();
                String dump = ChannelText.dump(new RateMap(zm, 1, 1));
                assertTrue(dump.contains("Inv = 1"));
                // The encapsulated ZoomMap loses its Invert card, as in the probe.
                StringBuilder edited = new StringBuilder();
                for (String line : dump.split("\n", -1)) {
                    if (!line.isEmpty() && !line.contains(" Invert = 1")) {
                        edited.append(line).append('\n');
                    }
                }
                RateMap rm = (RateMap) ChannelText.read(edited.toString());
                return rm.tran1(1, new double[] {1.0}, true)[0];
            }
            case "switchmap_forward":
                return switchmap(false).rate(new double[] {5.0}, 1, 1);
            case "switchmap_inverted_no_selector":
            case "switchmap_inverted_route": {
                SwitchMap sm = switchmap("switchmap_inverted_route".equals(name));
                sm.invert();
                return sm.rate(new double[] {50.0}, 1, 1);
            }
            default:
                throw new IllegalArgumentException("no Java construction for case " + name);
        }
    }

    @Test
    void ratesMatchTheCLibrary() {
        List<String> failures = new ArrayList<>();
        int ran = 0;
        for (JsonNode c : ProbeData.load("rate").get("CASES")) {
            String name = c.get(0).asText();
            double got = rate(name);
            double want = ProbeData.toDouble(c.get(1));
            if (Double.compare(got, want) != 0) {
                failures.add(name + ": " + got + ", C " + want);
            }
            ran++;
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
        assertEquals(8, ran, "cases run");
    }
}
