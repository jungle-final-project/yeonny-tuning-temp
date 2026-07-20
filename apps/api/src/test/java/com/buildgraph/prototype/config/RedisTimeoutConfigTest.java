package com.buildgraph.prototype.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * 캐시는 선택 기능인데 Redis가 매달리면 Build Chat 요청이 통째로 죽었다(감사 실측 60,003ms).
 * 원인은 command timeout 미설정 — Lettuce 기본값이 60초다. 값을 적어 두는 것만으로는 부족하고
 * 그 값이 실제로 Lettuce까지 도달해야 의미가 있어서, 커넥션 팩토리에서 되읽어 확인한다.
 */
class RedisTimeoutConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class));

    @Test
    void applicationYamlCommandTimeoutReachesLettuce() {
        runner.withPropertyValues(applicationYamlRedisProperties())
                .run(context -> {
                    LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
                    assertThat(factory.getClientConfiguration().getCommandTimeout())
                            .as("Redis가 응답하지 않을 때 캐시 조회 한 번이 요청 전체를 잡아먹지 않아야 한다")
                            .isEqualTo(Duration.ofMillis(250));
                });
    }

    @Test
    void commandTimeoutStaysWellBelowTheBuildChatRequestBudget() {
        runner.withPropertyValues(applicationYamlRedisProperties())
                .run(context -> {
                    LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
                    // 캐시 조회는 빠른 길을 타려고 하는 것이다 — 이 예산을 넘으면 캐시를 포기하는 편이 낫다.
                    assertThat(factory.getClientConfiguration().getCommandTimeout())
                            .isLessThanOrEqualTo(Duration.ofSeconds(1));
                });
    }

    /** application.yml에 적힌 값을 그대로 읽어 온다 — 테스트가 별도 숫자를 갖고 있으면 드리프트한다. */
    private static String[] applicationYamlRedisProperties() {
        String yaml = readApplicationYaml();
        return new String[] {
                "spring.data.redis.timeout=" + valueOf(yaml, "timeout: ${SPRING_DATA_REDIS_TIMEOUT:"),
                "spring.data.redis.connect-timeout=" + valueOf(yaml, "connect-timeout: ${SPRING_DATA_REDIS_CONNECT_TIMEOUT:")
        };
    }

    private static String valueOf(String yaml, String prefix) {
        int start = yaml.indexOf(prefix);
        if (start < 0) {
            throw new AssertionError("application.yml에 Redis timeout 설정이 없다: " + prefix);
        }
        int from = start + prefix.length();
        return yaml.substring(from, yaml.indexOf('}', from));
    }

    private static String readApplicationYaml() {
        try (var input = new ClassPathResource("application.yml").getInputStream()) {
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new AssertionError("application.yml을 읽지 못했다", error);
        }
    }
}
