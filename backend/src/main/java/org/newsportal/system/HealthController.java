package org.newsportal.system;

import java.util.Map;

import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public liveness/readiness summary for clients. Exposes only the overall
 * status (UP/DOWN); component details stay internal.
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

	private final HealthEndpoint healthEndpoint;

	public HealthController(HealthEndpoint healthEndpoint) {
		this.healthEndpoint = healthEndpoint;
	}

	@GetMapping
	public ResponseEntity<Map<String, String>> health() {
		String status = healthEndpoint.health().getStatus().getCode();
		HttpStatus httpStatus = "UP".equals(status) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
		return ResponseEntity.status(httpStatus).body(Map.of("status", status));
	}

}
