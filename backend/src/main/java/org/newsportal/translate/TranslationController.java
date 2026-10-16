package org.newsportal.translate;

import java.util.NoSuchElementException;

import org.newsportal.identity.AuthPrincipal;
import org.newsportal.translate.TranslationService.DailyLimitException;
import org.newsportal.translate.TranslationService.TranslationUnavailableException;
import org.newsportal.translate.TranslationService.TranslationView;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * GET: a cached translation, for anyone. POST: make one; readers only, because
 * each new translation costs money.
 */
@RestController
public class TranslationController {

	private final TranslationService translations;

	public TranslationController(TranslationService translations) {
		this.translations = translations;
	}

	@GetMapping("/api/v1/articles/{id}/translations/{language}")
	public ResponseEntity<TranslationView> cached(@PathVariable long id, @PathVariable String language) {
		return ResponseEntity.of(translations.cached(id, language));
	}

	@PostMapping("/api/v1/articles/{id}/translations/{language}")
	public TranslationView translate(@PathVariable long id, @PathVariable String language,
			@AuthenticationPrincipal AuthPrincipal reader) {
		return translations.translate(id, language, reader.userId());
	}

	@RestControllerAdvice(assignableTypes = TranslationController.class)
	@Order(Ordered.HIGHEST_PRECEDENCE)
	static class Errors {

		@ExceptionHandler(IllegalArgumentException.class)
		ProblemDetail badRequest(IllegalArgumentException ex) {
			return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
		}

		@ExceptionHandler(NoSuchElementException.class)
		ProblemDetail notFound(NoSuchElementException ex) {
			return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
		}

		@ExceptionHandler(IllegalStateException.class)
		ProblemDetail notAllowed(IllegalStateException ex) {
			return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
		}

		@ExceptionHandler(TranslationUnavailableException.class)
		ProblemDetail unavailable(TranslationUnavailableException ex) {
			return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
		}

		@ExceptionHandler(DailyLimitException.class)
		ProblemDetail limit(DailyLimitException ex) {
			return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
		}

		@ExceptionHandler(Translator.TranslationFailedException.class)
		ProblemDetail failed(Translator.TranslationFailedException ex) {
			return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "the translation could not be made; try again");
		}

	}

}
