package org.newsportal.admin;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.newsportal.admin.AdminDtos.CreateSourceRequest;
import org.newsportal.admin.AdminDtos.FeedHealthRow;
import org.newsportal.admin.AdminDtos.FeedView;
import org.newsportal.admin.AdminDtos.JobView;
import org.newsportal.admin.AdminDtos.QuarantineView;
import org.newsportal.admin.AdminDtos.ReviewView;
import org.newsportal.admin.AdminDtos.RightsRequest;
import org.newsportal.admin.AdminDtos.RightsView;
import org.newsportal.admin.AdminDtos.SourceDetail;
import org.newsportal.admin.AdminDtos.SourceView;
import org.newsportal.identity.AuditLog;
import org.newsportal.identity.AuthPrincipal;
import org.newsportal.ingestion.FeedOutcome;
import org.newsportal.ingestion.IngestionJob;
import org.newsportal.ingestion.IngestionJobRepository;
import org.newsportal.ingestion.IngestionService;
import org.newsportal.ingestion.QuarantinedRecordRepository;
import org.newsportal.ingestion.SourceFeed;
import org.newsportal.ingestion.SourceFeedRepository;
import org.newsportal.ingestion.SourceFeedService;
import org.newsportal.ingestion.UrlCanonicalizer;
import org.newsportal.source.ContentUse;
import org.newsportal.source.NewsSource;
import org.newsportal.source.NewsSourceRepository;
import org.newsportal.source.RightsDecision;
import org.newsportal.source.RightsPolicy;
import org.newsportal.source.RightsReview;
import org.newsportal.source.SourceReview;
import org.newsportal.source.SourceReviewRepository;
import org.newsportal.source.SourceRights;
import org.newsportal.source.SourceRightsRepository;
import org.newsportal.source.SourceRightsService;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff operations on publishers, rights and feeds (FR-075). Every change is
 * written to the audit log with the acting staff member (FR-076); rights
 * changes are also recorded in the source review history with their email.
 */
@Service
@Transactional
public class AdminSourceService {

	private static final Pattern CODE = Pattern.compile("[a-z0-9][a-z0-9-]{1,59}");

	private static final Set<String> LANGUAGES = Set.of("en", "hi", "ur", "ar");

	private static final Set<FeedOutcome> FAILURES = EnumSet.copyOf(
			EnumSet.allOf(FeedOutcome.class).stream().filter(FeedOutcome::isFailure).toList());

	private final NewsSourceRepository sources;

	private final SourceRightsRepository rights;

	private final SourceReviewRepository reviews;

	private final SourceRightsService rightsService;

	private final SourceFeedRepository feeds;

	private final SourceFeedService feedService;

	private final IngestionJobRepository jobs;

	private final QuarantinedRecordRepository quarantine;

	private final IngestionService ingestion;

	private final AuditLog audit;

	private final Clock clock;

	public AdminSourceService(NewsSourceRepository sources, SourceRightsRepository rights,
			SourceReviewRepository reviews, SourceRightsService rightsService, SourceFeedRepository feeds,
			SourceFeedService feedService, IngestionJobRepository jobs, QuarantinedRecordRepository quarantine,
			IngestionService ingestion, AuditLog audit, Clock clock) {
		this.sources = sources;
		this.rights = rights;
		this.reviews = reviews;
		this.rightsService = rightsService;
		this.feeds = feeds;
		this.feedService = feedService;
		this.jobs = jobs;
		this.quarantine = quarantine;
		this.ingestion = ingestion;
		this.audit = audit;
		this.clock = clock;
	}

	// --- reads ---

	@Transactional(readOnly = true)
	public List<SourceView> listSources() {
		return sources.findAll().stream()
			.sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
			.map(this::view)
			.toList();
	}

	@Transactional(readOnly = true)
	public SourceDetail sourceDetail(long sourceId) {
		NewsSource source = findSource(sourceId);
		List<ReviewView> history = reviews.findTop100BySourceIdOrderByIdDesc(sourceId).stream()
			.map(AdminSourceService::view)
			.toList();
		return new SourceDetail(view(source), history);
	}

	@Transactional(readOnly = true)
	public List<FeedHealthRow> healthDashboard() {
		List<FeedHealthRow> rows = new ArrayList<>();
		for (SourceFeed feed : feeds.findAll()) {
			NewsSource s = feed.getSource();
			FeedView v = view(feed);
			rows.add(new FeedHealthRow(v.id(), v.feedUrl(), s.getId(), s.getCode(), s.getName(), s.getStatus(),
					v.health(), v.lastOutcome(), v.lastAttemptAt(), v.lastSuccessAt(), v.consecutiveFailures(),
					v.nextRunAt()));
		}
		// Worst first.
		rows.sort((a, b) -> Integer.compare(severity(a.health()), severity(b.health())));
		return rows;
	}

	@Transactional(readOnly = true)
	public List<JobView> recentJobs(long feedId, int limit) {
		feedService.find(feedId);
		return jobs.findByFeedIdOrderByIdDesc(feedId, Limit.of(Math.clamp(limit, 1, 200))).stream()
			.map(AdminSourceService::view)
			.toList();
	}

	@Transactional(readOnly = true)
	public List<QuarantineView> quarantined(long jobId) {
		if (!jobs.existsById(jobId)) {
			throw new NoSuchElementException("job " + jobId + " not found");
		}
		return quarantine.findByJobId(jobId).stream()
			.map(q -> new QuarantineView(q.getReason(), q.getEntryGuid(), q.getEntryLink(), q.getDiagnostic()))
			.toList();
	}

	// --- publisher lifecycle ---

	public SourceView createSource(CreateSourceRequest req, AuthPrincipal actor) {
		if (req.code() == null || !CODE.matcher(req.code()).matches()) {
			throw new IllegalArgumentException("code must be 2-60 lowercase letters, digits or hyphens");
		}
		if (req.name() == null || req.name().isBlank() || req.name().length() > 200) {
			throw new IllegalArgumentException("name is required (max 200 characters)");
		}
		requireUrl(req.homepageUrl(), "homepageUrl");
		if (req.termsUrl() != null && !req.termsUrl().isBlank()) {
			requireUrl(req.termsUrl(), "termsUrl");
		}
		if (req.primaryLanguage() == null || !LANGUAGES.contains(req.primaryLanguage())) {
			throw new IllegalArgumentException("primaryLanguage must be one of " + LANGUAGES);
		}
		if (sources.findByCode(req.code()).isPresent()) {
			throw new IllegalStateException("a source with this code already exists");
		}
		NewsSource source = rightsService.proposeSource(req.code(), req.name().strip(), req.homepageUrl().strip(),
				req.primaryLanguage());
		source.setTermsUrl(blankToNull(req.termsUrl()));
		source.setTermsSummary(blankToNull(req.termsSummary()));
		audit.record(actor.userId(), "SOURCE_CREATED", "news_source", source.getId(), req.code());
		return view(source);
	}

	public SourceView updateTerms(long sourceId, String termsUrl, String termsSummary, String notes,
			AuthPrincipal actor) {
		requireNotes(notes);
		if (termsUrl != null && !termsUrl.isBlank()) {
			requireUrl(termsUrl, "termsUrl");
		}
		NewsSource source = findSource(sourceId);
		source.setTermsUrl(blankToNull(termsUrl));
		source.setTermsSummary(blankToNull(termsSummary));
		audit.record(actor.userId(), "SOURCE_TERMS_UPDATED", "news_source", sourceId, notes);
		return view(source);
	}

	public SourceView activate(long sourceId, String notes, AuthPrincipal actor) {
		rightsService.activateSource(sourceId, actor.email(), notes);
		audit.record(actor.userId(), "SOURCE_ACTIVATED", "news_source", sourceId, notes);
		return view(findSource(sourceId));
	}

	public SourceView suspend(long sourceId, String notes, AuthPrincipal actor) {
		rightsService.suspendSource(sourceId, actor.email(), notes);
		audit.record(actor.userId(), "SOURCE_SUSPENDED", "news_source", sourceId, notes);
		return view(findSource(sourceId));
	}

	public SourceView restore(long sourceId, String notes, AuthPrincipal actor) {
		rightsService.restoreSource(sourceId, actor.email(), notes);
		audit.record(actor.userId(), "SOURCE_RESTORED", "news_source", sourceId, notes);
		return view(findSource(sourceId));
	}

	public SourceView retire(long sourceId, String notes, AuthPrincipal actor) {
		rightsService.retireSource(sourceId, actor.email(), notes);
		audit.record(actor.userId(), "SOURCE_RETIRED", "news_source", sourceId, notes);
		return view(findSource(sourceId));
	}

	// --- rights ---

	public SourceView recordRights(long sourceId, ContentUse use, RightsRequest req, AuthPrincipal actor) {
		if (req.evidenceUrl() != null && !req.evidenceUrl().isBlank()) {
			requireUrl(req.evidenceUrl(), "evidenceUrl");
		}
		rightsService.recordRightsDecision(sourceId, use, new RightsReview(req.status(), req.validFrom(),
				req.expiresAt(), blankToNull(req.conditions()), actor.email(), req.notes(),
				blankToNull(req.evidenceUrl()), req.nextReviewDue()));
		audit.record(actor.userId(), "RIGHTS_DECISION", "news_source", sourceId, use + "=" + req.status());
		return view(findSource(sourceId));
	}

	public SourceView suspendUse(long sourceId, ContentUse use, String notes, AuthPrincipal actor) {
		rightsService.suspendUse(sourceId, use, actor.email(), notes);
		audit.record(actor.userId(), "RIGHTS_USE_SUSPENDED", "news_source", sourceId, use.name());
		return view(findSource(sourceId));
	}

	public SourceView restoreUse(long sourceId, ContentUse use, String notes, AuthPrincipal actor) {
		rightsService.restoreUse(sourceId, use, actor.email(), notes);
		audit.record(actor.userId(), "RIGHTS_USE_RESTORED", "news_source", sourceId, use.name());
		return view(findSource(sourceId));
	}

	// --- feeds ---

	public FeedView addFeed(long sourceId, String feedUrl, Integer intervalMinutes, AuthPrincipal actor) {
		SourceFeed feed = feedService.addFeed(sourceId, feedUrl, (intervalMinutes == null) ? 30 : intervalMinutes);
		audit.record(actor.userId(), "FEED_ADDED", "source_feed", feed.getId(), feed.getFeedUrl());
		return view(feed);
	}

	public FeedView updateFeed(long feedId, Boolean enabled, Integer intervalMinutes, AuthPrincipal actor) {
		SourceFeed feed = feedService.update(feedId, enabled, intervalMinutes);
		audit.record(actor.userId(), "FEED_UPDATED", "source_feed", feedId,
				"enabled=" + enabled + ", intervalMinutes=" + intervalMinutes);
		return view(feed);
	}

	/** Runs a feed immediately. Rights checks still apply. */
	@Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
	public JobView runFeedNow(long feedId, AuthPrincipal actor) {
		feedService.find(feedId);
		IngestionJob job = ingestion.runFeed(feedId);
		audit.record(actor.userId(), "FEED_RUN_MANUALLY", "source_feed", feedId, job.getOutcome().name());
		return view(job);
	}

	// --- mapping ---

	private SourceView view(NewsSource s) {
		Instant now = clock.instant();
		Map<ContentUse, SourceRights> byUse = rights.findBySourceId(s.getId()).stream()
			.collect(Collectors.toMap(SourceRights::getContentUse, Function.identity()));
		List<RightsView> rightsViews = new ArrayList<>();
		for (ContentUse use : ContentUse.values()) {
			SourceRights r = byUse.get(use);
			RightsDecision effective = RightsPolicy.evaluate(s, r, now);
			rightsViews.add((r == null)
					? new RightsView(use, null, null, null, null, false, null, false, effective.reason().name())
					: new RightsView(use, r.getStatus(), r.getValidFrom(), r.getExpiresAt(), r.getConditions(),
							r.isSuspended(), r.getSuspendedReason(), effective.allowed(), effective.reason().name()));
		}
		List<FeedView> feedViews = feeds.findBySourceIdOrderByIdAsc(s.getId()).stream().map(this::view).toList();
		return new SourceView(s.getId(), s.getCode(), s.getName(), s.getHomepageUrl(), s.getPrimaryLanguage(),
				s.getStatus(), s.getStatusReason(), s.getTermsUrl(), s.getTermsSummary(), rightsViews, feedViews);
	}

	private FeedView view(SourceFeed f) {
		Instant now = clock.instant();
		boolean failedRecently = jobs.existsByFeedIdAndOutcomeInAndStartedAtAfter(f.getId(), FAILURES,
				now.minus(FeedHealth.RECOVERY_WINDOW));
		FeedHealth health = FeedHealth.classify(f.isEnabled(), f.getLastOutcome(), f.getLastAttemptAt(),
				f.getLastSuccessAt(), f.getConsecutiveFailures(), f.refreshInterval(), failedRecently, now);
		return new FeedView(f.getId(), f.getFeedUrl(), f.getRefreshIntervalMinutes(), f.isEnabled(), health,
				f.getLastOutcome(), f.getLastAttemptAt(), f.getLastSuccessAt(), f.getConsecutiveFailures(),
				f.getNextRunAt());
	}

	private static ReviewView view(SourceReview r) {
		return new ReviewView(r.getId(), r.getContentUse(), r.getAction(), r.getPreviousStatus(), r.getNewStatus(),
				r.getValidFrom(), r.getExpiresAt(), r.getReviewer(), r.getNotes(), r.getEvidenceUrl(),
				r.getNextReviewDue(), r.getReviewedAt());
	}

	private static JobView view(IngestionJob j) {
		return new JobView(j.getId(), j.getStartedAt(), j.getFinishedAt(), j.getOutcome(), j.getHttpStatus(),
				j.getItemsSeen(), j.getItemsCreated(), j.getItemsUpdated(), j.getItemsUnchanged(),
				j.getItemsQuarantined(), j.getDiagnostic());
	}

	private static int severity(FeedHealth h) {
		return switch (h) {
			case FAILING -> 0;
			case STALE -> 1;
			case RECOVERING -> 2;
			case RIGHTS_RESTRICTED -> 3;
			case PENDING -> 4;
			case HEALTHY -> 5;
			case DISABLED -> 6;
		};
	}

	private NewsSource findSource(long id) {
		return sources.findById(id).orElseThrow(() -> new NoSuchElementException("source " + id + " not found"));
	}

	private static void requireUrl(String url, String field) {
		if (url == null || UrlCanonicalizer.canonicalize(url).isEmpty() || url.length() > 500) {
			throw new IllegalArgumentException(field + " must be an absolute http(s) URL (max 500 characters)");
		}
	}

	private static void requireNotes(String notes) {
		if (notes == null || notes.isBlank()) {
			throw new IllegalArgumentException("notes is required");
		}
	}

	private static String blankToNull(String s) {
		return (s == null || s.isBlank()) ? null : s.strip();
	}

}
