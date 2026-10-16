import DOMPurify from 'dompurify'

// Publisher links open in a new tab without giving the new page access to this one.
DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  if (node.tagName === 'A') {
    node.setAttribute('target', '_blank')
    node.setAttribute('rel', 'noopener noreferrer nofollow')
  }
})

const ALLOWED_TAGS = ['p', 'br', 'b', 'strong', 'i', 'em', 'u', 'a', 'ul', 'ol', 'li', 'blockquote', 'q', 'cite', 'code', 'pre', 'small', 'sub', 'sup', 'span']

/** Article HTML is sanitized again in the browser, even though the server already cleaned it. */
export function sanitize(html: string): string {
  return DOMPurify.sanitize(html, { ALLOWED_TAGS, ALLOWED_ATTR: ['href', 'cite', 'target', 'rel'] })
}

/** Visible text of an HTML fragment, for text-to-speech. */
export function plainText(html: string): string {
  return new DOMParser().parseFromString(html, 'text/html').body.textContent ?? ''
}
