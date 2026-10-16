package org.newsportal.me;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.newsportal.identity.AuthPrincipal;
import org.newsportal.identity.PrivacyService;
import org.newsportal.identity.RateLimiter;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.newsportal.me.MeDtos.CollectionView;
import org.newsportal.me.MeDtos.HistoryKind;
import org.newsportal.me.MeDtos.HistoryPage;
import org.newsportal.me.MeDtos.HistoryRequest;
import org.newsportal.me.MeDtos.NameRequest;
import org.newsportal.me.MeDtos.Preferences;
import org.newsportal.me.MeDtos.SavedPage;
import org.newsportal.reader.ReaderDtos.ArticlePage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
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

/** The signed-in reader's own data. Readers only (see SecurityConfig). */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

	private final MeService me;

	private final ExportService exports;

	private final PrivacyService privacy;

	private final RateLimiter limiter;

	public MeController(MeService me, ExportService exports, PrivacyService privacy, RateLimiter limiter) {
		this.me = me;
		this.exports = exports;
		this.privacy = privacy;
		this.limiter = limiter;
	}

	public record PasswordRequest(String password) {
	}

	// --- privacy (FR-049, FR-052) ---

	/** Downloads all personal data as JSON. Requires the current password. */
	@PostMapping("/export")
	public ResponseEntity<Map<String, Object>> export(@AuthenticationPrincipal AuthPrincipal user,
			@RequestBody PasswordRequest body) {
		limiter.acquire("export:" + user.userId(), 5, Duration.ofHours(1));
		Map<String, Object> data = exports.export(user.userId(), body.password());
		return ResponseEntity.ok()
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment().filename("news-portal-my-data.json").build().toString())
			.cacheControl(CacheControl.noStore())
			.body(data);
	}

	/**
	 * Starts account deletion. Signs the reader out everywhere at once; the
	 * account can be restored by signing in until {@code deletionDueAt}.
	 */
	@PostMapping("/account/delete")
	public ResponseEntity<Map<String, String>> deleteAccount(@AuthenticationPrincipal AuthPrincipal user,
			@RequestBody PasswordRequest body, HttpServletRequest request) {
		limiter.acquire("delete:" + user.userId(), 5, Duration.ofHours(1));
		Instant due = privacy.requestDeletion(user, body.password());
		HttpSession session = request.getSession(false);
		if (session != null) {
			session.invalidate();
		}
		return ResponseEntity.accepted().body(Map.of("deletionDueAt", due.toString()));
	}

	// --- preferences ---

	@GetMapping("/preferences")
	public Preferences preferences(@AuthenticationPrincipal AuthPrincipal user) {
		return me.preferences(user.userId());
	}

	@PutMapping("/preferences")
	public Preferences updatePreferences(@AuthenticationPrincipal AuthPrincipal user, @RequestBody Preferences body) {
		return me.updatePreferences(user.userId(), body);
	}

	// --- feeds ---

	@GetMapping("/for-you")
	public ArticlePage forYou(@AuthenticationPrincipal AuthPrincipal user,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "0") int size) {
		return me.forYou(user.userId(), cursor, size);
	}

	@GetMapping("/for-you/other-languages")
	public ArticlePage otherLanguages(@AuthenticationPrincipal AuthPrincipal user,
			@RequestParam(defaultValue = "5") int size) {
		return me.otherLanguages(user.userId(), size);
	}

	// --- bookmarks ---

	@GetMapping("/bookmarks")
	public SavedPage bookmarks(@AuthenticationPrincipal AuthPrincipal user,
			@RequestParam(name = "q", required = false) String text, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "0") int size) {
		return me.bookmarks(user.userId(), text, page, size);
	}

	@GetMapping("/bookmarks/{articleId}")
	public Map<String, Boolean> isBookmarked(@AuthenticationPrincipal AuthPrincipal user,
			@PathVariable long articleId) {
		return Map.of("bookmarked", me.isBookmarked(user.userId(), articleId));
	}

	@PutMapping("/bookmarks/{articleId}")
	public ResponseEntity<Void> bookmark(@AuthenticationPrincipal AuthPrincipal user, @PathVariable long articleId) {
		me.addBookmark(user.userId(), articleId);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/bookmarks/{articleId}")
	public ResponseEntity<Void> unbookmark(@AuthenticationPrincipal AuthPrincipal user, @PathVariable long articleId) {
		me.removeBookmark(user.userId(), articleId);
		return ResponseEntity.noContent().build();
	}

	// --- collections ---

	@GetMapping("/collections")
	public List<CollectionView> collections(@AuthenticationPrincipal AuthPrincipal user) {
		return me.collections(user.userId());
	}

	@PostMapping("/collections")
	@ResponseStatus(HttpStatus.CREATED)
	public CollectionView createCollection(@AuthenticationPrincipal AuthPrincipal user, @RequestBody NameRequest body) {
		return me.createCollection(user.userId(), body.name());
	}

	@PatchMapping("/collections/{id}")
	public CollectionView renameCollection(@AuthenticationPrincipal AuthPrincipal user, @PathVariable long id,
			@RequestBody NameRequest body) {
		return me.renameCollection(user.userId(), id, body.name());
	}

	@DeleteMapping("/collections/{id}")
	public ResponseEntity<Void> deleteCollection(@AuthenticationPrincipal AuthPrincipal user, @PathVariable long id) {
		me.deleteCollection(user.userId(), id);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/collections/{id}/items")
	public SavedPage collectionItems(@AuthenticationPrincipal AuthPrincipal user, @PathVariable long id,
			@RequestParam(name = "q", required = false) String text, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "0") int size) {
		return me.collectionItems(user.userId(), id, text, page, size);
	}

	@PutMapping("/collections/{id}/items/{articleId}")
	public ResponseEntity<Void> addItem(@AuthenticationPrincipal AuthPrincipal user, @PathVariable long id,
			@PathVariable long articleId) {
		me.addToCollection(user.userId(), id, articleId);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/collections/{id}/items/{articleId}")
	public ResponseEntity<Void> removeItem(@AuthenticationPrincipal AuthPrincipal user, @PathVariable long id,
			@PathVariable long articleId) {
		me.removeFromCollection(user.userId(), id, articleId);
		return ResponseEntity.noContent().build();
	}

	// --- history ---

	/** The app calls this when the reader opens or listens to an article. */
	@PostMapping("/history")
	public Map<String, Boolean> recordHistory(@AuthenticationPrincipal AuthPrincipal user,
			@RequestBody HistoryRequest body) {
		return Map.of("recorded", me.recordHistory(user.userId(), body.articleId(), body.kind()));
	}

	@GetMapping("/history")
	public HistoryPage history(@AuthenticationPrincipal AuthPrincipal user,
			@RequestParam(required = false) HistoryKind kind, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "0") int size) {
		return me.history(user.userId(), kind, page, size);
	}

	@DeleteMapping("/history/{articleId}")
	public ResponseEntity<Void> deleteHistoryItem(@AuthenticationPrincipal AuthPrincipal user,
			@PathVariable long articleId, @RequestParam(required = false) HistoryKind kind) {
		me.deleteHistoryItem(user.userId(), articleId, kind);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/history")
	public Map<String, Integer> clearHistory(@AuthenticationPrincipal AuthPrincipal user,
			@RequestParam(required = false) HistoryKind kind) {
		return Map.of("deleted", me.clearHistory(user.userId(), kind));
	}

}
