package org.newsportal;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Test profile only: drops and re-applies every migration so each test run
 * proves the migrations work on a clean database.
 */
@Configuration
@Profile("test")
class CleanMigrateTestConfig {

	@Bean
	FlywayMigrationStrategy cleanMigrate() {
		return flyway -> {
			flyway.clean();
			flyway.migrate();
		};
	}

}
