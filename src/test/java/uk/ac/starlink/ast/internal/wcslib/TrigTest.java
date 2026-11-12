package uk.ac.starlink.ast.internal.wcslib;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The tests of Rust {@code src/wcslib/trig.rs} ({@code mod tests}) that test its {@code Libm}
 * trigonometry, which is {@link Trig} here.
 *
 * <p>Deferred as {@code hardware SIMD}: the tests of the branchless {@code Kernel} trigonometry and
 * of the predicates saying which arguments it covers ({@code
 * kernel_trig_is_within_two_ulp_of_libm_where_it_covers}, {@code
 * sincosd_covers_is_the_remainder_test_within_the_domain}, {@code
 * sincosd_covers_is_the_rounding_test_for_every_input}, {@code
 * atand_builds_agree_at_extreme_arguments}, {@code covers_rejects_wcstrig_exact_arguments}), since
 * the Java port has no kernels.
 */
class TrigTest {

    /** The port's own values beyond the range of an {@code int} quadrant count, the same on every platform. */
    @Test
    void hugeMultiplesOf90HaveTheAarch64Values() {
        double a = 90.0 * 1099511627776.0; // 90 * 2^40
        assertEquals(Double.doubleToRawLongBits(0.0), Double.doubleToRawLongBits(Trig.cosd(a)));
        assertEquals(Double.doubleToRawLongBits(0.0), Double.doubleToRawLongBits(Trig.sind(a)));
        assertEquals(Double.doubleToRawLongBits(1.0), Double.doubleToRawLongBits(Trig.cosd(-a)));
        assertEquals(Double.doubleToRawLongBits(1.0), Double.doubleToRawLongBits(Trig.sind(-a)));
    }
}
