package org.newsportal.identity;

import java.io.Serializable;
import java.util.Set;

import org.newsportal.identity.AppUser.AccountType;

/** What the session remembers about the signed-in user. */
public record AuthPrincipal(long userId, String email, AccountType accountType, Set<String> staffRoles,
		String securityStamp) implements Serializable {

	public boolean isStaff() {
		return accountType == AccountType.STAFF;
	}

}
