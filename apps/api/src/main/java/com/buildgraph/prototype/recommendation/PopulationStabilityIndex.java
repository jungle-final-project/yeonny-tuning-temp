package com.buildgraph.prototype.recommendation;

import java.util.Arrays;

/**
 * Population Stability Index(M3 drift, 설계 §4). 기준(reference) 분포의 10분위로 구간을 잡고, 두 분포의
 * 구간별 비율 차를 PSI = Σ (a% − e%)·ln(a%/e%) 로 요약한다. 0-빈도 구간은 ε=1e-4로 스무딩한다.
 *
 * 통상 해석: PSI < 0.1 안정, 0.1~0.2 경미, > 0.2 유의(경고), > 0.3 심각. 순수 함수라 DB 없이 테스트 가능.
 */
final class PopulationStabilityIndex {

    private static final double EPSILON = 1e-4;
    private static final int BINS = 10;

    private PopulationStabilityIndex() {
    }

    /** 기준(expected) 분포 10분위 구간에서 actual 분포의 PSI. 어느 한쪽이 비면 null. */
    static Double psi(double[] expected, double[] actual) {
        if (expected.length == 0 || actual.length == 0) {
            return null;
        }
        double[] edges = decileEdges(expected);
        double[] expectedCounts = new double[BINS];
        double[] actualCounts = new double[BINS];
        for (double value : expected) {
            expectedCounts[binOf(value, edges)] += 1;
        }
        for (double value : actual) {
            actualCounts[binOf(value, edges)] += 1;
        }
        double psi = 0.0;
        for (int i = 0; i < BINS; i += 1) {
            double expectedPct = Math.max(expectedCounts[i] / expected.length, EPSILON);
            double actualPct = Math.max(actualCounts[i] / actual.length, EPSILON);
            psi += (actualPct - expectedPct) * Math.log(actualPct / expectedPct);
        }
        return psi;
    }

    /** 9개 내부 경계(10%, 20%, …, 90% nearest-rank 분위)로 10구간을 만든다. */
    private static double[] decileEdges(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        double[] edges = new double[BINS - 1];
        for (int k = 0; k < edges.length; k += 1) {
            double p = (k + 1) / (double) BINS;
            int index = (int) Math.ceil(p * sorted.length) - 1;
            edges[k] = sorted[Math.max(0, Math.min(sorted.length - 1, index))];
        }
        return edges;
    }

    // 값이 속하는 구간 인덱스(0..BINS-1). 경계보다 큰 경계 개수 = 구간 인덱스, 마지막 구간에 클램프.
    private static int binOf(double value, double[] edges) {
        int bin = 0;
        while (bin < edges.length && value > edges[bin]) {
            bin += 1;
        }
        return bin;
    }
}
