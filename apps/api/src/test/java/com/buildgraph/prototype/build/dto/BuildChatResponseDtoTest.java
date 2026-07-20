package com.buildgraph.prototype.build.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class BuildChatResponseDtoTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void fullBuildResponseOmitsUnusedPartField() throws Exception {
        BuildChatResponseDto response = new BuildChatResponseDto(
                BuildChatResponseDto.OutputType.FULL_BUILD,
                "전체 견적을 추천드릴게요.",
                new BuildChatResponseDto.Build(
                        450_000,
                        List.of(new BuildChatResponseDto.Part(
                                "91319be3-0a26-493e-81a0-38d6b665eff6",
                                "CPU",
                                "추천 CPU",
                                "BuildGraph",
                                450_000,
                                "성능과 가성비 점수를 기준으로 선택했습니다."
                        ))
                ),
                null,
                "b079b482-c51d-487d-a2e6-29b75c95c8ea"
        );

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));

        assertThat(json.path("outputType").asText()).isEqualTo("FULL_BUILD");
        assertThat(json.has("build")).isTrue();
        assertThat(json.has("part")).isFalse();
    }
}
