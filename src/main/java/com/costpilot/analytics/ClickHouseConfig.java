package com.costpilot.analytics;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

// 5.3/5.4: the ClickHouse read/write path is exposed ONLY as a named JdbcTemplate, never
// as a DataSource bean. That is deliberate: declaring a DataSource bean would make Spring
// Boot back off its Postgres datasource auto-config, and then Flyway/JPA could bind to the
// wrong datasource (Flyway even fails with "Unsupported Database: ClickHouse"). By keeping
// the ClickHouse DataSource private inside this @Bean method, Boot's auto-configured
// Postgres datasource stays the one and only DataSource bean - Flyway and Hibernate keep
// using it, and @ServiceConnection wiring in tests keeps working. Guarded by
// costpilot.clickhouse.enabled so dev/tests without ClickHouse are unaffected.
@Configuration
@EnableConfigurationProperties(ClickHouseProperties.class)
@ConditionalOnProperty(name = "costpilot.clickhouse.enabled", havingValue = "true")
public class ClickHouseConfig {

	@Bean
	JdbcTemplate clickhouseJdbcTemplate(ClickHouseProperties props) {
		HikariConfig config = new HikariConfig();
		config.setJdbcUrl(props.getJdbcUrl());
		config.setUsername(props.getUsername());
		config.setPassword(props.getPassword());
		config.setDriverClassName("com.clickhouse.jdbc.ClickHouseDriver");
		config.setPoolName("clickhouse-pool");
		config.setMaximumPoolSize(8);
		DataSource clickHouseDataSource = new HikariDataSource(config);
		return new JdbcTemplate(clickHouseDataSource);
	}
}
