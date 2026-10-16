package org.newsportal.mail;

import java.util.Arrays;
import java.util.Set;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
@EnableConfigurationProperties(MailProperties.class)
public class MailConfig {

	private static final Set<String> MODES = Set.of("smtp", "outbox", "capture");

	public MailConfig(MailProperties props, Environment env) {
		if (props.mode() == null || !MODES.contains(props.mode())) {
			throw new IllegalStateException("newsportal.mail.mode must be one of " + MODES);
		}
		boolean pilot = Arrays.asList(env.getActiveProfiles()).contains("pilot");
		if (pilot && !"smtp".equals(props.mode())) {
			throw new IllegalStateException("The pilot profile requires newsportal.mail.mode=smtp");
		}
		if (props.publicBaseUrl() == null || props.publicBaseUrl().isBlank()) {
			throw new IllegalStateException("newsportal.mail.public-base-url is required");
		}
	}

}
