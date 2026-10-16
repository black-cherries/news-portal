import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { I18nProvider, useI18n } from './I18nProvider'
import { LANGUAGES, messages } from './messages'

describe('dictionaries', () => {
  it('every language has every key, with no empty strings', () => {
    const keys = Object.keys(messages.en).sort()
    for (const lang of LANGUAGES) {
      expect(Object.keys(messages[lang]).sort(), lang).toEqual(keys)
      for (const [key, value] of Object.entries(messages[lang])) {
        expect(value.trim(), `${lang} ${key}`).not.toBe('')
      }
    }
  })

  it('placeholders match the English text', () => {
    for (const [key, english] of Object.entries(messages.en)) {
      const expected = (english.match(/\{\w+\}/g) ?? []).sort()
      for (const lang of LANGUAGES) {
        const text = messages[lang][key as keyof typeof messages.en]
        expect((text.match(/\{\w+\}/g) ?? []).sort(), `${lang} ${key}`).toEqual(expected)
      }
    }
  })
})

function Probe() {
  const { t, setLang } = useI18n()
  return (
    <>
      <p>{t('nav.latest')}</p>
      <button onClick={() => setLang('ur')}>urdu</button>
      <button onClick={() => setLang('hi')}>hindi</button>
    </>
  )
}

describe('I18nProvider', () => {
  it('sets document language and direction, RTL for Urdu', async () => {
    render(
      <I18nProvider initial="en">
        <Probe />
      </I18nProvider>,
    )
    expect(document.documentElement.dir).toBe('ltr')
    expect(screen.getByText('Latest')).toBeInTheDocument()

    await userEvent.click(screen.getByText('urdu'))
    expect(document.documentElement.lang).toBe('ur')
    expect(document.documentElement.dir).toBe('rtl')
    expect(screen.getByText('تازہ ترین')).toBeInTheDocument()

    await userEvent.click(screen.getByText('hindi'))
    expect(document.documentElement.dir).toBe('ltr')
    expect(screen.getByText('ताज़ा')).toBeInTheDocument()
  })
})
