package org.newsportal.notify;

import org.newsportal.identity.AuthPrincipal;
import org.newsportal.notify.NotificationDtos.AlertPage;
import org.newsportal.notify.NotificationDtos.AlertRequest;
import org.newsportal.notify.NotificationDtos.AlertView;
import org.newsportal.notify.NotificationDtos.CancelRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Breaking-news alerts are chosen by content reviewers (FR-065, FR-067). */
@RestController
@RequestMapping("/api/v1/admin/alerts")
@PreAuthorize("hasRole('CONTENT_REVIEWER')")
public class AdminAlertController {

	private final NotificationService notifications;

	public AdminAlertController(NotificationService notifications) {
		this.notifications = notifications;
	}

	@GetMapping
	public AlertPage list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return notifications.alerts(page, size);
	}

	@GetMapping("/{id}")
	public AlertView get(@PathVariable long id) {
		return notifications.alert(id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public AlertView create(@RequestBody AlertRequest body, @AuthenticationPrincipal AuthPrincipal me) {
		return notifications.createAlert(body, me);
	}

	@PostMapping("/{id}/cancel")
	public AlertView cancel(@PathVariable long id, @RequestBody CancelRequest body,
			@AuthenticationPrincipal AuthPrincipal me) {
		return notifications.cancelAlert(id, body.reason(), me);
	}

}
