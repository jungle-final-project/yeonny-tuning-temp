package com.buildgraph.prototype.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** M3 PSI 순수 계산 테스트. */
class PopulationStabilityIndexTest {

    private static double[] range(int from, int toExclusive) {
        return IntStream.range(from, toExclusive).asDoubleStream().toArray();
    }

    @Test
    void identicalDistributionsAreNearZero() {
        double[] data = range(0, 1000);
        Double psi = PopulationStabilityIndex.psi(data, data);
        assertThat(psi).isNotNull();
        assertThat(psi).isCloseTo(0.0, within(0.01));
    }

    @Test
    void smallShiftIsSmallPsi() {
        double[] expected = range(0, 1000);
        double[] actual = range(50, 1050); // 소폭 이동
        Double psi = PopulationStabilityIndex.psi(expected, actual);
        assertThat(psi).isNotNull();
        assertThat(psi).isBetween(0.0, 0.25);
    }

    @Test
    void largeShiftExceedsSevereThreshold() {
        double[] expected = range(0, 1000);
        double[] actual = range(2000, 3000); // 완전 분리
        Double psi = PopulationStabilityIndex.psi(expected, actual);
        assertThat(psi).isNotNull();
        assertThat(psi).isGreaterThan(0.3);
    }

    @Test
    void psiIsMonotonicInShiftMagnitude() {
        double[] expected = range(0, 1000);
        Double small = PopulationStabilityIndex.psi(expected, range(100, 1100));
        Double large = PopulationStabilityIndex.psi(expected, range(500, 1500));
        assertThat(large).isGreaterThan(small);
    }

    @Test
    void emptyInputReturnsNull() {
        assertThat(PopulationStabilityIndex.psi(new double[0], range(0, 10))).isNull();
        assertThat(PopulationStabilityIndex.psi(range(0, 10), new double[0])).isNull();
    }

    @Test
    void constantExpectedHandledWithoutError() {
        // 모든 기준값이 동일해도 예외 없이 계산되고, actual이 다르면 PSI > 0.
        double[] constant = new double[500];
        java.util.Arrays.fill(constant, 5.0);
        double[] shifted = range(0, 500);
        Double psi = PopulationStabilityIndex.psi(constant, shifted);
        assertThat(psi).isNotNull();
        assertThat(psi).isGreaterThan(0.0);
    }
}
