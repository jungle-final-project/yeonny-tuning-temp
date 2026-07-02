package com.buildgraph.prototype.config;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class FlywayLegacyAgentMigrationStrategy {
    private static final Logger log = LoggerFactory.getLogger(FlywayLegacyAgentMigrationStrategy.class);

    @Bean
    FlywayMigrationStrategy legacyAgentMigrationStrategy() {
        return flyway -> {
            if (hasLegacyAgentVersionCollision(flyway)) {
                log.warn("Detected legacy agent V53/V54 migration history. Running Flyway repair before migrate.");
                flyway.repair();
            }
            flyway.migrate();
        };
    }

    private boolean hasLegacyAgentVersionCollision(Flyway flyway) {
        DataSource dataSource = flyway.getConfiguration().getDataSource();
        String table = flyway.getConfiguration().getTable();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        "SELECT version, description FROM " + table + " WHERE version IN ('53', '54')")) {
            Map<String, String> descriptions = new HashMap<>();
            while (resultSet.next()) {
                descriptions.put(resultSet.getString("version"), resultSet.getString("description"));
            }
            return "pc agent gold mode contract".equals(descriptions.get("53"))
                    && "agent idempotency records".equals(descriptions.get("54"));
        } catch (SQLException ex) {
            if ("42P01".equals(ex.getSQLState())) {
                return false;
            }
            throw new IllegalStateException("Failed to inspect Flyway schema history", ex);
        }
    }
}
