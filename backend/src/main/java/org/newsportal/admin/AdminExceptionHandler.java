package org.newsportal.admin;

import java.util.NoSuchElementException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Error mapping for the staff API only. Domain services signal bad input with
 * IllegalArgumentException and invalid state changes with
 * IllegalStateException; their messages are written for staff.
 */
@RestControllerAdvice(basePackages = "org.newsportal.admin")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminExceptionHandler {

	@ExceptionHandler(IllegalArgumentException.class)
	ProblemDetail badRequest(IllegalArgumentException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
	}

	@ExceptionHandler(IllegalStateException.class)
	ProblemDetail conflict(IllegalStateException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
	}

	@ExceptionHandler(NoSuchElementException.class)
	ProblemDetail notFound(NoSuchElementException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
	}

}
