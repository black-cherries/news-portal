package org.newsportal.identity;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Creates the first system administrator at startup when
 * {@code NEWSPORTAL_BOOTSTRAP_ADMIN_EMAIL} and
 * {@code NEWSPORTAL_BOOTSTRAP_ADMIN_PASSWORD} are set. Does nothing if the
 * account already exists. Remove both variables after first use.
 */
@Component
@ConditionalOnProperty("newsportal.bootstrap.admin-email")
public class AdminBootstrap implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

	private final AccountService accounts;

	private final AppUserRepository users;

	private final String email;

	private final String password;

	public AdminBootstrap(AccountService accounts, AppUserRepository users,
			@Value("${newsportal.bootstrap.admin-email}") String email,
			@Value("${newsportal.bootstrap.admin-password:}") String password) {
		this.accounts = accounts;
		this.users = users;
		this.email = email;
		this.password = password;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (users.findByEmail(email.strip().toLowerCase(java.util.Locale.ROOT)).isPresent()) {
			log.info("Bootstrap admin already exists; nothing to do. Remove the bootstrap variables.");
			return;
		}
		if (password.isBlank()) {
			throw new IllegalStateException("NEWSPORTAL_BOOTSTRAP_ADMIN_PASSWORD must be set");
		}
		AppUser admin = accounts.createStaffAccount(email, password, List.of("SYSTEM_ADMIN"), null);
		log.info("Created bootstrap system administrator (user id {}). Remove the bootstrap variables now.",
				admin.getId());
	}

}
