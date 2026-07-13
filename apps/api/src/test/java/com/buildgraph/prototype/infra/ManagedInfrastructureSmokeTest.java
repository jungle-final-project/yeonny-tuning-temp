package com.buildgraph.prototype.infra;

import com.rabbitmq.client.Address;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("managed-infrastructure")
class ManagedInfrastructureSmokeTest {

    @Test
    void rdsHasVectorExtensionAndFlywayHistory() throws Exception {
        requireManagedInfrastructureTest();

        try (var connection = DriverManager.getConnection(
                required("SPRING_DATASOURCE_URL"),
                required("SPRING_DATASOURCE_USERNAME"),
                required("SPRING_DATASOURCE_PASSWORD"))) {
            try (var statement = connection.createStatement();
                    var result = statement.executeQuery(
                            "SELECT extversion FROM pg_extension WHERE extname = 'vector'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("extversion")).isNotBlank();
            }

            try (var statement = connection.createStatement();
                    var result = statement.executeQuery(
                            "SELECT COUNT(*) AS migration_count FROM flyway_schema_history WHERE success = true")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong("migration_count")).isPositive();
            }
        }
    }

    @Test
    void elasticacheSupportsAuthenticatedTlsReadWriteAndTtl() {
        requireManagedInfrastructureTest();

        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(
                required("SPRING_DATA_REDIS_HOST"),
                Integer.parseInt(required("SPRING_DATA_REDIS_PORT")));
        optional("SPRING_DATA_REDIS_USERNAME").ifPresent(server::setUsername);
        optional("SPRING_DATA_REDIS_PASSWORD")
                .map(RedisPassword::of)
                .ifPresent(server::setPassword);

        LettuceClientConfiguration client = booleanEnvironment(
                "SPRING_DATA_REDIS_SSL_ENABLED", true)
                ? LettuceClientConfiguration.builder().useSsl().build()
                : LettuceClientConfiguration.builder().build();
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(server, client);
        connectionFactory.afterPropertiesSet();

        StringRedisTemplate redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        String key = "buildgraph:phase1:smoke:" + UUID.randomUUID();
        try {
            redis.opsForValue().set(key, "ok", Duration.ofSeconds(30));

            assertThat(redis.opsForValue().get(key)).isEqualTo("ok");
            assertThat(redis.getExpire(key, TimeUnit.SECONDS)).isBetween(1L, 30L);
        } finally {
            redis.delete(key);
            connectionFactory.destroy();
        }
    }

    @Test
    void amazonMqSupportsTlsPublishAndConsume() throws Exception {
        requireManagedInfrastructureTest();

        ConnectionFactory factory = rabbitConnectionFactory();
        try (Connection connection = factory.newConnection(rabbitAddresses(), "phase1-smoke");
                Channel channel = connection.createChannel()) {
            String queue = "buildgraph.phase1.smoke." + UUID.randomUUID();
            channel.queueDeclare(queue, false, true, true, null);
            channel.basicPublish("", queue, null, "ok".getBytes(StandardCharsets.UTF_8));

            GetResponse response = channel.basicGet(queue, true);

            assertThat(response).isNotNull();
            assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).isEqualTo("ok");
        }
    }

    @Test
    void amazonMqAcceptsANewConnectionAfterClientDisconnect() throws Exception {
        requireManagedInfrastructureTest();

        ConnectionFactory factory = rabbitConnectionFactory();
        try (Connection first = factory.newConnection(rabbitAddresses(), "phase1-reconnect-first")) {
            assertThat(first.isOpen()).isTrue();
        }
        try (Connection second = factory.newConnection(rabbitAddresses(), "phase1-reconnect-second")) {
            assertThat(second.isOpen()).isTrue();
        }
    }

    private static ConnectionFactory rabbitConnectionFactory() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setUsername(required("SPRING_RABBITMQ_USERNAME"));
        factory.setPassword(required("SPRING_RABBITMQ_PASSWORD"));
        factory.setVirtualHost(environment("SPRING_RABBITMQ_VIRTUAL_HOST", "/"));
        factory.setConnectionTimeout(10_000);
        factory.setHandshakeTimeout(10_000);
        factory.setAutomaticRecoveryEnabled(true);
        if (booleanEnvironment("SPRING_RABBITMQ_SSL_ENABLED", true)) {
            factory.useSslProtocol();
            factory.enableHostnameVerification();
        }
        return factory;
    }

    private static Address[] rabbitAddresses() {
        return Address.parseAddresses(required("SPRING_RABBITMQ_ADDRESSES"));
    }

    private static void requireManagedInfrastructureTest() {
        Assumptions.assumeTrue(
                booleanEnvironment("MANAGED_INFRA_TEST_ENABLED", false),
                "Set MANAGED_INFRA_TEST_ENABLED=true only from a host that can reach private AWS endpoints");
    }

    private static String required(String name) {
        String value = System.getenv(name);
        assertThat(value)
                .as("required environment variable %s", name)
                .isNotBlank();
        return value;
    }

    private static java.util.Optional<String> optional(String name) {
        return java.util.Optional.ofNullable(System.getenv(name)).filter(value -> !value.isBlank());
    }

    private static String environment(String name, String defaultValue) {
        return optional(name).orElse(defaultValue);
    }

    private static boolean booleanEnvironment(String name, boolean defaultValue) {
        return Boolean.parseBoolean(environment(name, Boolean.toString(defaultValue)));
    }
}
