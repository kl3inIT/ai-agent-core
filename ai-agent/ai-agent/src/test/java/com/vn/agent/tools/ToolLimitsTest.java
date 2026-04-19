package com.vn.agent.tools;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the TOOL-06 limit constants and every branch of {@link ToolLimits#clampLimit}
 * (D-14). Any future change to DEFAULT_LIMIT / MAX_LIMIT / DEFAULT_MAX_FILTER_DEPTH
 * or to the clamp semantics fails this test — the numbers are contractual (Plan 04
 * threat register T-03-22).
 */
class ToolLimitsTest {

    @Test
    void constantsMatchContract() {
        assertThat(ToolLimits.DEFAULT_LIMIT).isEqualTo(20);
        assertThat(ToolLimits.MAX_LIMIT).isEqualTo(100);
        assertThat(ToolLimits.DEFAULT_MAX_FILTER_DEPTH).isEqualTo(3);
    }

    @Test
    void nullYieldsDefault() {
        assertThat(ToolLimits.clampLimit(null)).isEqualTo(20);
    }

    @Test
    void clampsBelowOneToOne() {
        assertThat(ToolLimits.clampLimit(-5)).isEqualTo(1);
        assertThat(ToolLimits.clampLimit(0)).isEqualTo(1);
    }

    @Test
    void passesThroughValid() {
        assertThat(ToolLimits.clampLimit(1)).isEqualTo(1);
        assertThat(ToolLimits.clampLimit(20)).isEqualTo(20);
        assertThat(ToolLimits.clampLimit(50)).isEqualTo(50);
        assertThat(ToolLimits.clampLimit(100)).isEqualTo(100);
    }

    @Test
    void clampsAboveMax() {
        assertThat(ToolLimits.clampLimit(101)).isEqualTo(100);
        assertThat(ToolLimits.clampLimit(1000)).isEqualTo(100);
        assertThat(ToolLimits.clampLimit(Integer.MAX_VALUE)).isEqualTo(100);
    }
}
