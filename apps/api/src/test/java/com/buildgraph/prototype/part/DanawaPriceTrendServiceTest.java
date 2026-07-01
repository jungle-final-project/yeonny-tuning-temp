package com.buildgraph.prototype.part;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DanawaPriceTrendServiceTest {

    @Test
    void productMatchRejectsDifferentGpuClass() {
        assertThat(DanawaPriceTrendService.productMismatchReason(
                "GPU",
                "MSI 지포스 RTX 5080 뱅가드 SOC D7 16GB",
                "MSI",
                "MSI 지포스 RTX 5090 게이밍 트리오 OC D7 32GB"
        )).isPresent();
    }

    @Test
    void productMatchRejectsPowerBankForPsu() {
        assertThat(DanawaPriceTrendService.productMismatchReason(
                "PSU",
                "마이크로닉스 Classic II 풀체인지 600W 80PLUS BRONZE ATX 3.1",
                "Micronics",
                "에코플로우 리버3 맥스 파워뱅크"
        )).isPresent();
    }

    @Test
    void productMatchAcceptsExactManualBackfillGpu() {
        assertThat(DanawaPriceTrendService.productMismatchReason(
                "GPU",
                "ASUS PRIME 지포스 RTX 5070 Ti D7 16GB 인텍앤컴퍼니",
                "ASUS",
                "ASUS PRIME 지포스 RTX 5070 Ti D7 16GB 대원씨티에스"
        )).isEmpty();
    }

    @Test
    void productMatchRejectsDifferentRamCapacity() {
        assertThat(DanawaPriceTrendService.productMismatchReason(
                "RAM",
                "CORSAIR Vengeance DDR5 RAM 32GB(2x16GB) 6400MHz",
                "Corsair",
                "CORSAIR DDR5-6400 CL32 VENGEANCE RGB WHITE 패키지 (64GB(32Gx2))"
        )).isPresent();
    }

    @Test
    void productMatchRejectsDifferentCaseGeneration() {
        assertThat(DanawaPriceTrendService.productMismatchReason(
                "CASE",
                "프렉탈디자인 Meshify 3 XL 블랙",
                "Fractal Design",
                "Fractal Design Meshify 2 XL Light 강화유리"
        )).isPresent();
    }

    @Test
    void productMatchRejectsDifferentColorWhenBothSidesSpecifyColor() {
        assertThat(DanawaPriceTrendService.productMismatchReason(
                "CASE",
                "리안리 LANCOOL 217 INF 화이트",
                "리안리",
                "리안리 LANCOOL 217 (블랙)"
        )).isPresent();
    }

    @Test
    void productMatchRejectsDifferentGpuLine() {
        assertThat(DanawaPriceTrendService.productMismatchReason(
                "GPU",
                "ASUS PRIME 지포스 RTX 5080 OC D7 16GB",
                "ASUS",
                "ASUS ROG Astral 지포스 RTX 5080 OC D7 16GB"
        )).isPresent();
    }

    @Test
    void productMatchRejectsKoreanGpuLineMismatch() {
        assertThat(DanawaPriceTrendService.productMismatchReason(
                "GPU",
                "MSI 지포스 RTX 5070 Ti 벤투스 3X OC D7 16GB",
                "MSI",
                "MSI 지포스 RTX 5070 Ti 게이밍 트리오 OC D7 16GB 트라이프로져4"
        )).isPresent();
    }

    @Test
    void productMatchRejectsMotherboardWifiVariantMismatch() {
        assertThat(DanawaPriceTrendService.productMismatchReason(
                "MOTHERBOARD",
                "ASRock B850 Pro-A 디앤디컴",
                "ASRock",
                "ASRock B850 Pro-A WiFi 디앤디컴"
        )).isPresent();
    }

    @Test
    void productMatchRejectsPsuEfficiencyMismatch() {
        assertThat(DanawaPriceTrendService.productMismatchReason(
                "PSU",
                "마이크로닉스 Classic II 풀체인지 700W 80PLUS BRONZE ATX3.1",
                "Micronics",
                "마이크로닉스 Classic II 풀체인지 700W 80PLUS실버 ATX3.1"
        )).isPresent();
    }
}
