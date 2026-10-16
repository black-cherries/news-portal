package org.newsportal.web;

import org.newsportal.identity.RateLimiter.RateLimitedException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Turns client mistakes into 4xx responses with a safe, short message. */
@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(InvalidRequestException.class)
	ProblemDetail invalid(InvalidRequestException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ProblemDetail typeMismatch(MethodArgumentTypeMismatchException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "invalid value for " + ex.getName());
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ProblemDetail unreadable(HttpMessageNotReadableException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "request body is missing or malformed");
	}

	@ExceptionHandler(RateLimitedException.class)
	ResponseEntity<ProblemDetail> rateLimited(RateLimitedException ex) {
		long seconds = Math.max(1, ex.getRetryAfter().toSeconds());
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
			.header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
			.body(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, "too many attempts; try again later"));
	}

}
