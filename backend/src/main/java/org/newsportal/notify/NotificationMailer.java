package org.newsportal.notify;

import java.util.List;
import java.util.Map;

import org.newsportal.mail.MailGateway;
import org.newsportal.mail.MailGateway.OutgoingMail;
import org.newsportal.mail.MailProperties;
import org.newsportal.notify.NotificationDtos.Frequency;
import org.springframework.stereotype.Component;

/**
 * Digest and breaking-news emails in the reader's digest language (FR-068).
 * Articles keep their original title; any article in another language is
 * labelled with that language, because translations are a later phase.
 *
 * The Hindi, Urdu and Arabic texts are drafts and need a native-speaker
 * review before the pilot, like the interface text.
 *
 * Sends on the calling thread and throws if delivery fails, so the caller can
 * record the outcome.
 */
@Component
public class NotificationMailer {

	/** Who gets the email. {@code language} is the digest language. */
	public record Recipient(long userId, String email, String language, String unsubscribeToken) {
	}

	/** One story. {@code alsoFrom}: other publishers with the same headline. */
	public record Item(long articleId, String title, String language, String sourceName, List<String> alsoFrom) {
	}

	private record Text(String digestSubjectDaily, String digestSubjectWeekly, String digestIntroDaily,
			String digestIntroWeekly, String inLanguage, String alsoFrom, String alertSubject, String alertIntro,
			String footerDigest, String footerAlert, String manage, String unsubscribe,
			Map<String, String> languageNames) {
	}

	private static final Map<String, Text> TEXTS = Map.of(
			"en", new Text("Your daily News Portal digest", "Your weekly News Portal digest",
					"New articles from the past day that match your settings:",
					"New articles from the past week that match your settings:", "In %s",
					"Same headline also from: %s", "Breaking news: %s",
					"The News Portal team marked this story as breaking news:",
					"You get this email because you turned on email digests.",
					"You get this email because you turned on breaking-news alerts.",
					"Change what you receive: %s", "Unsubscribe: %s",
					Map.of("en", "English", "hi", "Hindi", "ur", "Urdu", "ar", "Arabic")),
			"hi", new Text("आपका दैनिक News Portal सारांश", "आपका साप्ताहिक News Portal सारांश",
					"पिछले एक दिन के नए लेख जो आपकी सेटिंग्स से मेल खाते हैं:",
					"पिछले एक सप्ताह के नए लेख जो आपकी सेटिंग्स से मेल खाते हैं:", "%s में",
					"यही शीर्षक इनसे भी: %s", "ब्रेकिंग न्यूज़: %s",
					"News Portal टीम ने इस ख़बर को ब्रेकिंग न्यूज़ के रूप में चिह्नित किया है:",
					"आपको यह ईमेल इसलिए मिला क्योंकि आपने ईमेल सारांश चालू किया है।",
					"आपको यह ईमेल इसलिए मिला क्योंकि आपने ब्रेकिंग न्यूज़ अलर्ट चालू किए हैं।",
					"आपको क्या मिले, यह बदलें: %s", "सदस्यता छोड़ें: %s",
					Map.of("en", "अंग्रेज़ी", "hi", "हिन्दी", "ur", "उर्दू", "ar", "अरबी")),
			"ur", new Text("آپ کا روزانہ News Portal خلاصہ", "آپ کا ہفتہ وار News Portal خلاصہ",
					"پچھلے ایک دن کے نئے مضامین جو آپ کی ترتیبات سے ملتے ہیں:",
					"پچھلے ایک ہفتے کے نئے مضامین جو آپ کی ترتیبات سے ملتے ہیں:", "%s میں",
					"یہی سرخی ان سے بھی: %s", "بریکنگ نیوز: %s",
					"News Portal ٹیم نے اس خبر کو بریکنگ نیوز قرار دیا ہے:",
					"آپ کو یہ ای میل اس لیے ملی کیونکہ آپ نے ای میل خلاصہ آن کیا ہے۔",
					"آپ کو یہ ای میل اس لیے ملی کیونکہ آپ نے بریکنگ نیوز الرٹس آن کیے ہیں۔",
					"آپ کو کیا ملے، یہ تبدیل کریں: %s", "رکنیت ختم کریں: %s",
					Map.of("en", "انگریزی", "hi", "ہندی", "ur", "اردو", "ar", "عربی")),
			"ar", new Text("ملخصك اليومي من News Portal", "ملخصك الأسبوعي من News Portal",
					"مقالات جديدة من اليوم الماضي تطابق إعداداتك:", "مقالات جديدة من الأسبوع الماضي تطابق إعداداتك:",
					"باللغة %s", "العنوان نفسه أيضًا من: %s", "خبر عاجل: %s",
					"صنّف فريق News Portal هذا الخبر كخبر عاجل:",
					"تصلك هذه الرسالة لأنك فعّلت الملخصات عبر البريد الإلكتروني.",
					"تصلك هذه الرسالة لأنك فعّلت تنبيهات الأخبار العاجلة.", "غيّر ما يصلك: %s",
					"إلغاء الاشتراك: %s",
					Map.of("en", "الإنجليزية", "hi", "الهندية", "ur", "الأردية", "ar", "العربية")));

	private final MailGateway gateway;

	private final String baseUrl;

	public NotificationMailer(MailGateway gateway, MailProperties props) {
		this.gateway = gateway;
		this.baseUrl = props.publicBaseUrl().replaceAll("/+$", "");
	}

	public void sendDigest(Recipient to, Frequency frequency, List<Item> items) {
		Text t = text(to.language());
		boolean daily = frequency == Frequency.DAILY;
		StringBuilder body = new StringBuilder(daily ? t.digestIntroDaily() : t.digestIntroWeekly()).append("\n\n");
		for (int i = 0; i < items.size(); i++) {
			body.append(i + 1).append(". ");
			appendItem(body, t, to.language(), items.get(i));
			body.append('\n');
		}
		appendFooter(body, t, t.footerDigest(), to, "DIGEST");
		gateway.send(new OutgoingMail(to.email(), daily ? t.digestSubjectDaily() : t.digestSubjectWeekly(),
				body.toString()));
	}

	public void sendAlert(Recipient to, Item item) {
		Text t = text(to.language());
		StringBuilder body = new StringBuilder(t.alertIntro()).append("\n\n");
		appendItem(body, t, to.language(), item);
		body.append('\n');
		appendFooter(body, t, t.footerAlert(), to, "ALERTS");
		gateway.send(new OutgoingMail(to.email(), t.alertSubject().formatted(subjectSafe(item.title())),
				body.toString()));
	}

	private void appendItem(StringBuilder body, Text t, String language, Item item) {
		body.append(oneLine(item.title())).append('\n');
		body.append("   ").append(oneLine(item.sourceName()));
		if (!item.language().equals(language)) {
			// FR-068: say clearly that this one is in its original language.
			String name = t.languageNames().getOrDefault(item.language(), item.language());
			body.append(" · ").append(t.inLanguage().formatted(name));
		}
		body.append('\n');
		body.append("   ").append(baseUrl).append("/articles/").append(item.articleId()).append('\n');
		if (!item.alsoFrom().isEmpty()) {
			body.append("   ").append(t.alsoFrom().formatted(oneLine(String.join(", ", item.alsoFrom())))).append('\n');
		}
	}

	private void appendFooter(StringBuilder body, Text t, String why, Recipient to, String kind) {
		body.append("--\n").append(why).append('\n');
		body.append(t.manage().formatted(baseUrl + "/settings")).append('\n');
		body.append(t.unsubscribe().formatted(baseUrl + "/unsubscribe#token=" + to.unsubscribeToken() + "&kind=" + kind))
			.append('\n');
	}

	private static Text text(String language) {
		Text t = (language == null) ? null : TEXTS.get(language);
		return (t == null) ? TEXTS.get("en") : t;
	}

	/** Feed text is untrusted: keep it on one line. */
	private static String oneLine(String value) {
		return (value == null) ? "" : value.replaceAll("[\\p{Cntrl}\\u2028\\u2029]+", " ").strip();
	}

	private static String subjectSafe(String title) {
		String line = oneLine(title);
		return (line.codePointCount(0, line.length()) <= 120) ? line
				: line.substring(0, line.offsetByCodePoints(0, 119)) + "…";
	}

}
