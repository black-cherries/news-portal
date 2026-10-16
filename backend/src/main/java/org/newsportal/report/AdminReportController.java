package org.newsportal.report;

import java.util.Map;

import org.newsportal.identity.AuthPrincipal;
import org.newsportal.report.ReportDtos.AssignRequest;
import org.newsportal.report.ReportDtos.StaffPage;
import org.newsportal.report.ReportDtos.StaffReport;
import org.newsportal.report.ReportDtos.Status;
import org.newsportal.report.ReportDtos.StatusRequest;
import org.newsportal.report.ReportDtos.Type;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Report handling for content reviewers; counts only for analysts (SRS §4.3). */
@RestController
@RequestMapping("/api/v1/admin/reports")
public class AdminReportController {

	private final ReportService reports;

	public AdminReportController(ReportService reports) {
		this.reports = reports;
	}

	@GetMapping
	@PreAuthorize("hasRole('CONTENT_REVIEWER')")
	public StaffPage list(@RequestParam(required = false) Status status, @RequestParam(required = false) Type type,
			@RequestParam(defaultValue = "false") boolean mine, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size, @AuthenticationPrincipal AuthPrincipal me) {
		return reports.list(status, type, mine ? me.userId() : null, page, size);
	}

	@GetMapping("/{id}")
	@PreAuthorize("hasRole('CONTENT_REVIEWER')")
	public StaffReport get(@PathVariable long id) {
		return reports.get(id);
	}

	@PostMapping("/{id}/assign")
	@PreAuthorize("hasRole('CONTENT_REVIEWER')")
	public StaffReport assign(@PathVariable long id, @RequestBody AssignRequest body,
			@AuthenticationPrincipal AuthPrincipal me) {
		return reports.assign(id, body.assigneeId(), me);
	}

	@PostMapping("/{id}/status")
	@PreAuthorize("hasRole('CONTENT_REVIEWER')")
	public StaffReport status(@PathVariable long id, @RequestBody StatusRequest body,
			@AuthenticationPrincipal AuthPrincipal me) {
		return reports.changeStatus(id, body, me);
	}

	@GetMapping("/stats")
	@PreAuthorize("hasAnyRole('CONTENT_REVIEWER', 'ANALYST')")
	public Map<String, Object> stats() {
		return reports.stats();
	}

}
