package org.newsportal.admin;

import java.util.List;
import java.util.Locale;

import org.newsportal.admin.AdminDtos.CreateFeedRequest;
import org.newsportal.admin.AdminDtos.CreateSourceRequest;
import org.newsportal.admin.AdminDtos.FeedHealthRow;
import org.newsportal.admin.AdminDtos.FeedView;
import org.newsportal.admin.AdminDtos.JobView;
import org.newsportal.admin.AdminDtos.NotesRequest;
import org.newsportal.admin.AdminDtos.QuarantineView;
import org.newsportal.admin.AdminDtos.RightsRequest;
import org.newsportal.admin.AdminDtos.SourceDetail;
import org.newsportal.admin.AdminDtos.SourceView;
import org.newsportal.admin.AdminDtos.UpdateFeedRequest;
import org.newsportal.admin.AdminDtos.UpdateTermsRequest;
import org.newsportal.identity.AuthPrincipal;
import org.newsportal.source.ContentUse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publisher, rights and feed administration. Any staff role may read; only
 * source managers may change anything (SRS §4.3).
 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('STAFF')")
public class AdminSourceController {

	static final String SOURCE_MANAGER = "hasRole('SOURCE_MANAGER')";

	private final AdminSourceService admin;

	public AdminSourceController(AdminSourceService admin) {
		this.admin = admin;
	}

	// --- reads (any staff) ---

	@GetMapping("/sources")
	public List<SourceView> sources() {
		return admin.listSources();
	}

	@GetMapping("/sources/{id}")
	public SourceDetail source(@PathVariable long id) {
		return admin.sourceDetail(id);
	}

	@GetMapping("/source-health")
	public List<FeedHealthRow> health() {
		return admin.healthDashboard();
	}

	@GetMapping("/feeds/{id}/jobs")
	public List<JobView> jobs(@PathVariable long id, @RequestParam(defaultValue = "20") int limit) {
		return admin.recentJobs(id, limit);
	}

	@GetMapping("/jobs/{id}/quarantine")
	public List<QuarantineView> quarantine(@PathVariable long id) {
		return admin.quarantined(id);
	}

	// --- changes (source managers) ---

	@PostMapping("/sources")
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize(SOURCE_MANAGER)
	public SourceView create(@RequestBody CreateSourceRequest body, @AuthenticationPrincipal AuthPrincipal me) {
		return admin.createSource(body, me);
	}

	@PatchMapping("/sources/{id}/terms")
	@PreAuthorize(SOURCE_MANAGER)
	public SourceView terms(@PathVariable long id, @RequestBody UpdateTermsRequest body,
			@AuthenticationPrincipal AuthPrincipal me) {
		return admin.updateTerms(id, body.termsUrl(), body.termsSummary(), body.notes(), me);
	}

	@PostMapping("/sources/{id}/activate")
	@PreAuthorize(SOURCE_MANAGER)
	public SourceView activate(@PathVariable long id, @RequestBody NotesRequest body,
			@AuthenticationPrincipal AuthPrincipal me) {
		return admin.activate(id, body.notes(), me);
	}

	@PostMapping("/sources/{id}/suspend")
	@PreAuthorize(SOURCE_MANAGER)
	public SourceView suspend(@PathVariable long id, @RequestBody NotesRequest body,
			@AuthenticationPrincipal AuthPrincipal me) {
		return admin.suspend(id, body.notes(), me);
	}

	@PostMapping("/sources/{id}/restore")
	@PreAuthorize(SOURCE_MANAGER)
	public SourceView restore(@PathVariable long id, @RequestBody NotesRequest body,
			@AuthenticationPrincipal AuthPrincipal me) {
		return admin.restore(id, body.notes(), me);
	}

	@PostMapping("/sources/{id}/retire")
	@PreAuthorize(SOURCE_MANAGER)
	public SourceView retire(@PathVariable long id, @RequestBody NotesRequest body,
			@AuthenticationPrincipal AuthPrincipal me) {
		return admin.retire(id, body.notes(), me);
	}

	@PutMapping("/sources/{id}/rights/{use}")
	@PreAuthorize(SOURCE_MANAGER)
	public SourceView rights(@PathVariable long id, @PathVariable String use, @RequestBody RightsRequest body,
			@AuthenticationPrincipal AuthPrincipal me) {
		return admin.recordRights(id, parseUse(use), body, me);
	}

	@PostMapping("/sources/{id}/rights/{use}/suspend")
	@PreAuthorize(SOURCE_MANAGER)
	public SourceView suspendUse(@PathVariable long id, @PathVariable String use, @RequestBody NotesRequest body,
			@AuthenticationPrincipal AuthPrincipal me) {
		return admin.suspendUse(id, parseUse(use), body.notes(), me);
	}

	@PostMapping("/sources/{id}/rights/{use}/restore")
	@PreAuthorize(SOURCE_MANAGER)
	public SourceView restoreUse(@PathVariable long id, @PathVariable String use, @RequestBody NotesRequest body,
			@AuthenticationPrincipal AuthPrincipal me) {
		return admin.restoreUse(id, parseUse(use), body.notes(), me);
	}

	@PostMapping("/sources/{id}/feeds")
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize(SOURCE_MANAGER)
	public FeedView addFeed(@PathVariable long id, @RequestBody CreateFeedRequest body,
			@AuthenticationPrincipal AuthPrincipal me) {
		return admin.addFeed(id, body.feedUrl(), body.refreshIntervalMinutes(), me);
	}

	@PatchMapping("/feeds/{id}")
	@PreAuthorize(SOURCE_MANAGER)
	public FeedView updateFeed(@PathVariable long id, @RequestBody UpdateFeedRequest body,
			@AuthenticationPrincipal AuthPrincipal me) {
		return admin.updateFeed(id, body.enabled(), body.refreshIntervalMinutes(), me);
	}

	@PostMapping("/feeds/{id}/run")
	@PreAuthorize(SOURCE_MANAGER)
	public JobView run(@PathVariable long id, @AuthenticationPrincipal AuthPrincipal me) {
		return admin.runFeedNow(id, me);
	}

	/** Accepts {@code full-text-display} or {@code FULL_TEXT_DISPLAY}. */
	static ContentUse parseUse(String raw) {
		try {
			return ContentUse.valueOf(raw.strip().replace('-', '_').toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("unknown content use '" + raw + "'");
		}
	}

}
