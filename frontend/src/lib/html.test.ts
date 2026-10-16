import { describe, expect, it } from 'vitest'
import { plainText, sanitize } from './html'

describe('sanitize', () => {
  it('removes scripts, event handlers and unsafe URLs', () => {
    const out = sanitize(
      '<p onclick="steal()">Hi <img src=x onerror="alert(1)"><script>alert(2)</script><a href="javascript:alert(3)">x</a></p>',
    )
    expect(out).not.toMatch(/script|onerror|onclick|javascript:|<img/i)
    expect(out).toContain('Hi')
  })

  it('makes links open safely in a new tab', () => {
    const out = sanitize('<a href="https://publisher.example/story">source</a>')
    expect(out).toContain('target="_blank"')
    expect(out).toContain('rel="noopener noreferrer nofollow"')
  })
})

describe('plainText', () => {
  it('returns visible text only', () => {
    expect(plainText('<p>Hello <b>world</b></p>')).toBe('Hello world')
  })
})
