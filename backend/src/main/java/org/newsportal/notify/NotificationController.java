package org.newsportal.notify;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.newsportal.identity.AuthPrincipal;
import org.newsportal.notify.NotificationDtos.Settings;
import org.newsportal.notify.NotificationDtos.UnsubscribeRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Readers' digest and alert settings, and the unsubscribe link in every notification email. */
@RestController
public class NotificationController {

	private final NotificationService notifications;

	public NotificationController(NotificationService notifications) {
		this.notifications = notifications;
	}

	@GetMapping("/api/v1/me/notifications")
	public Settings settings(@AuthenticationPrincipal AuthPrincipal user) {
		return notifications.settings(user.userId());
	}

	@PutMapping("/api/v1/me/notifications")
	public Settings update(@AuthenticationPrincipal AuthPrincipal user, @RequestBody Settings body) {
		return notifications.updateSettings(user.userId(), body);
	}

	/** Public: the token from the email is the only credential, and it can only turn emails off. */
	@PostMapping("/api/v1/notifications/unsubscribe")
	public Map<String, Boolean> unsubscribe(@RequestBody UnsubscribeRequest body, HttpServletRequest request) {
		notifications.unsubscribe(body.token(), body.kind(), request.getRemoteAddr());
		return Map.of("unsubscribed", true);
	}

}
