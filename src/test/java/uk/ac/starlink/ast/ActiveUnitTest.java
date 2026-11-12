package uk.ac.starlink.ast;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Translation of Rust {@code tests/test_active_unit.rs}: ActiveUnit and the matching of Frames.
 *
 * <p>{@code overlay_transfers_active_unit} runs here (frame.c {@code Overlay}). The tests that
 * match a template against a target ({@code active_unit_inserts_scale_for_km_to_m}, {@code
 * active_unit_off_ignores_units}, {@code active_unit_multi_axis_scales_per_axis} and {@code
 * active_unit_aborts_whole_match_when_one_axis_unmappable}) are translated in Task 17.
 */
class ActiveUnitTest {

    @Test
    void overlayTransfersActiveUnit() {
        Frame result = new Frame(1);
        Frame template = new Frame(1);
        template.setActiveUnit(true);
        template.overlay(new int[] {0}, result);
        assertTrue(result.getActiveUnit());
    }
}
