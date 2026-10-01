package com.potg.don.demo.admission;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@Profile("demo")
public class DemoAdmissionConfiguration {
    @Bean
    SmartInitializingSingleton demoAdmissionSchemaVerifier(JdbcTemplate jdbc,
        @Value("${app.demo.max-visitors:1000}") int expectedMax) {
        return () -> {
            try {
                jdbc.execute((ConnectionCallback<Void>) connection -> {
                    DemoAdmissionSchema.verify(connection);
                    DemoAdmissionSchema.verifySnapshot(connection, expectedMax);
                    return null;
                });
            } catch (RuntimeException failure) {
                // Do not chain vendor exceptions containing SQL/connection identity into startup diagnostics.
                String diagnostic = "DEMO_ADMISSION_SQL_UNAVAILABLE";
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                    if (cause instanceof java.sql.SQLException sql && "HY000".equals(sql.getSQLState())
                        && sql.getErrorCode() == 0 && sql.getMessage() != null
                        && sql.getMessage().matches("DEMO_(SCHEMA|CAPACITY)_[A-Z_]{1,64}")) diagnostic = sql.getMessage();
                }
                throw new IllegalStateException("DEMO_ADMISSION_SCHEMA_OR_INTEGRITY_INVALID",
                    new IllegalStateException(diagnostic));
            }
        };
    }
}
