package org.newsportal.report;

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.newsportal.identity.AuthPrincipal;
import org.newsportal.report.ReportDtos.MyReport;
import org.newsportal.report.ReportDtos.SubmitRequest;
import org.newsportal.report.ReportDtos.Submitted;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Submitting reports (anyone) and following them (signed-in readers). */
@RestController
public class ReportController {

	private final ReportService reports;

	public ReportController(ReportService reports) {
		this.reports = reports;
	}

	public record NotifyRequest(boolean notifyMe) {
	}

	/** Guests may report too; only signed-in readers can follow a report. */
	@PostMapping("/api/v1/reports")
	@ResponseStatus(HttpStatus.CREATED)
	public Submitted submit(@RequestBody SubmitRequest body, @AuthenticationPrincipal AuthPrincipal me,
			@RequestHeader(name = "X-UI-Language", required = false) String uiLanguage, HttpServletRequest request) {
		return reports.submit(body, me, uiLanguage, request.getRemoteAddr());
	}

	@GetMapping("/api/v1/me/reports")
	public List<MyReport> mine(@AuthenticationPrincipal AuthPrincipal me) {
		return reports.myReports(me.userId());
	}

	@PutMapping("/api/v1/me/reports/{reference}/notify")
	public ResponseEntity<Map<String, Boolean>> setNotify(@AuthenticationPrincipal AuthPrincipal me,
			@PathVariable String reference, @RequestBody NotifyRequest body) {
		reports.setNotify(me.userId(), reference, body.notifyMe());
		return ResponseEntity.ok(Map.of("notifyMe", body.notifyMe()));
	}

}
