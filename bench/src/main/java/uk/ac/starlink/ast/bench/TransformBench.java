package uk.ac.starlink.ast.bench;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import uk.ac.starlink.ast.BenchAccess;
import uk.ac.starlink.ast.Mapping;

/**
 * Times the transforms of the benchmark cases ({@link BenchCases}) at several point counts.
 *
 * <p>An operation is one call transforming {@code npoints} points, so JMH's primary score is the
 * time per call. Each call also adds {@code npoints} to the {@link Points} counter, which JMH
 * reports as the secondary result {@code <benchmark>:points}, the time per point. The points are
 * the section's oracle rows repeated in order, chosen as {@code ast-bench} chooses them.
 *
 * <p>{@link #tranP} is the public entry point a caller holding one array per axis uses, as {@code
 * ast-bench}'s {@code rust-cols} timing is; it allocates its result on each call. {@link
 * #nanProtocol} is the NaN protocol with its boundary passes and buffers allocated once, as {@code
 * ast-bench}'s {@code rust-nan} timing is.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class TransformBench {

    /** The number of points transformed, which JMH reports per unit of time as {@code points}. */
    @State(Scope.Thread)
    @AuxCounters(AuxCounters.Type.OPERATIONS)
    public static class Points {
        /** The points transformed in the current iteration. */
        public long points;

        /** Starts each iteration's count at zero. */
        @Setup(Level.Iteration)
        public void reset() {
            points = 0;
        }
    }

    /** The case, {@code <oracle>:<relpath>:<dir>}; {@code BenchCases} lists those that load. */
    @Param({
        "framesets:wcsconv/framesets/lsst-ast.ast:forward",
        "framesets:wcsconv/framesets/lsst-ast.ast:inverse",
        "framesets:wcsconv/framesets/tnx-cheb-head.ast:forward",
        "framesets:wcsconv/framesets/tnx-cheb-head.ast:inverse",
        "framesets:wcsconv/framesets/sip2-head.ast:forward",
        "framesets:wcsconv/framesets/sip2-head.ast:inverse",
        "framesets:wcsconv/framesets/dss-ast.ast:forward",
        "framesets:wcsconv/framesets/dss-ast.ast:inverse",
        "framesets:wcsconv/framesets/sao-tan-head.ast:forward",
        "framesets:wcsconv/framesets/sao-tan-head.ast:inverse",
        "simplify_fixtures:simplify/win_parallel_merge.map:forward",
        "simplify_fixtures:simplify/win_parallel_merge.map:inverse",
        "simplify_fixtures:simplify/cmpmap_nested_parallel_flatten.map:forward",
        "simplify_fixtures:simplify/cmpmap_nested_parallel_flatten.map:inverse",
        "bench:cmpmap_parallel.map:forward",
        "bench:cmpmap_parallel.map:inverse",
        "bench:cmpmap_series.map:forward",
        "bench:cmpmap_series.map:inverse",
        "bench:matrixmap_3x3_diag.map:forward",
        "bench:matrixmap_3x3_diag.map:inverse",
        "bench:matrixmap_3x3_full.map:forward",
        "bench:matrixmap_3x3_full.map:inverse",
        "bench:permmap_2d_swap.map:forward",
        "bench:permmap_2d_swap.map:inverse",
        "bench:polymap_2d_deg5.map:forward",
        "bench:polymap_2d_deg5.map:inverse",
        "bench:shiftmap_2d.map:forward",
        "bench:shiftmap_2d.map:inverse",
        "bench:slamap_eqgal.map:forward",
        "bench:slamap_eqgal.map:inverse",
        "bench:slamap_amp.map:forward",
        "bench:slamap_amp.map:inverse",
        "bench:slamap_fk45z.map:forward",
        "bench:slamap_fk45z.map:inverse",
        "bench:slamap_prec.map:forward",
        "bench:slamap_prec.map:inverse",
        "bench:sphmap.map:forward",
        "bench:sphmap.map:inverse",
        "bench:timemap_taiutc.map:forward",
        "bench:timemap_taiutc.map:inverse",
        "bench:timemap_mjdtobep.map:forward",
        "bench:timemap_mjdtobep.map:inverse",
        "bench:unitmap_2d.map:forward",
        "bench:unitmap_2d.map:inverse",
        "bench:wcsmap_sin.map:forward",
        "bench:wcsmap_sin.map:inverse",
        "bench:wcsmap_tan.map:forward",
        "bench:wcsmap_tan.map:inverse",
        "bench:wcsmap_zpn.map:forward",
        "bench:wcsmap_zpn.map:inverse",
        "bench:wcsmap_arc.map:forward",
        "bench:wcsmap_arc.map:inverse",
        "bench:wcsmap_cea.map:forward",
        "bench:wcsmap_cea.map:inverse",
        "bench:wcsmap_ait.map:forward",
        "bench:wcsmap_ait.map:inverse",
        "bench:wcsmap_cod.map:forward",
        "bench:wcsmap_cod.map:inverse",
        "bench:wcsmap_bon.map:forward",
        "bench:wcsmap_bon.map:inverse",
        "bench:wcsmap_qsc.map:forward",
        "bench:wcsmap_qsc.map:inverse",
        "bench:wcsmap_hpx.map:forward",
        "bench:wcsmap_hpx.map:inverse",
        "bench:winmap_2d.map:forward",
        "bench:winmap_2d.map:inverse",
        "bench:zoommap_2d.map:forward",
        "bench:zoommap_2d.map:inverse",
        "framesets:wcsconv/inputs/lsst.ast:forward",
        "framesets:wcsconv/inputs/lsst.ast:inverse",
        "framesets:wcsconv/inputs/dss.ast:forward",
        "framesets:wcsconv/inputs/dss.ast:inverse",
        "framesets:wcsconv/inputs/timj.ast:forward",
        "framesets:wcsconv/inputs/timj.ast:inverse",
        "simplify_fixtures:simplify/lsst1.map:forward",
        "simplify_fixtures:simplify/lsst1.map:inverse",
        "simplify_fixtures:simplify/lsst.map:forward",
        "simplify_fixtures:simplify/lsst.map:inverse"
    })
    public String fixture;

    /** The number of points in each call. */
    @Param({"1", "1000", "100000", "1000000"})
    public int npoints;

    private Mapping map;
    private boolean forward;
    private int ncoordIn;
    private int ncoordOut;
    private double[][] in;
    private double[][] nanIn;
    private double[][] nanOut;

    /** Loads the case and builds its points and buffers. */
    @Setup(Level.Trial)
    public void setUp() {
        BenchCases.Case c = BenchCases.load(fixture);
        map = c.mapping;
        forward = c.section.forward;
        ncoordIn = c.section.nin;
        ncoordOut = c.section.nout;
        in = BenchCases.columns(c.section, BenchCases.selectRows(c.section, npoints));
        nanIn = new double[ncoordIn][npoints];
        nanOut = new double[ncoordOut][npoints];
    }

    /** Transforms the points through {@link Mapping#tranP}. */
    @Benchmark
    public double[][] tranP(Points count) {
        count.points += npoints;
        return map.tranP(npoints, ncoordIn, in, forward, ncoordOut);
    }

    /** Transforms the points through the NaN protocol, into buffers allocated once. */
    @Benchmark
    public double[][] nanProtocol(Points count) {
        count.points += npoints;
        BenchAccess.nanEdges(map, in, npoints, forward, nanIn, nanOut);
        return nanOut;
    }
}
