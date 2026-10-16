import { useEffect, useRef, useState } from 'react'
import { useI18n } from '../i18n/I18nProvider'

interface Props {
  /** Text the publisher's rights allow to be read aloud (FR-059). */
  text: string
  /** The article's language, used to pick a matching voice (FR-060). */
  language: string
  summaryOnly: boolean
  onStart?: () => void
}

type State = 'idle' | 'playing' | 'paused'

function findVoice(language: string): SpeechSynthesisVoice | undefined {
  return window.speechSynthesis
    .getVoices()
    .find((v) => v.lang.toLowerCase() === language || v.lang.toLowerCase().startsWith(`${language}-`))
}

/** Browser text-to-speech. Fails gracefully when there is no voice for the language. */
export function ListenButton({ text, language, summaryOnly, onStart }: Props) {
  const { t } = useI18n()
  const supported = typeof window !== 'undefined' && 'speechSynthesis' in window
  const [state, setState] = useState<State>('idle')
  const [hasVoice, setHasVoice] = useState(() => supported && findVoice(language) !== undefined)
  const utterance = useRef<SpeechSynthesisUtterance | null>(null)

  // Voices load asynchronously in some browsers.
  useEffect(() => {
    if (!supported) return
    const update = () => setHasVoice(findVoice(language) !== undefined)
    update()
    window.speechSynthesis.addEventListener('voiceschanged', update)
    return () => {
      window.speechSynthesis.removeEventListener('voiceschanged', update)
      window.speechSynthesis.cancel()
    }
  }, [supported, language])

  if (!supported) return <p className="hint">{t('listen.unsupported')}</p>
  if (!hasVoice) return <p className="hint">{t('listen.noVoice')}</p>

  const start = () => {
    const u = new SpeechSynthesisUtterance(text)
    u.lang = language
    const voice = findVoice(language)
    if (voice) u.voice = voice
    u.onend = () => setState('idle')
    u.onerror = () => setState('idle')
    utterance.current = u
    window.speechSynthesis.cancel()
    window.speechSynthesis.speak(u)
    setState('playing')
    onStart?.()
  }

  return (
    <div className="listen" role="group" aria-label={t('listen.listen')}>
      {state === 'idle' && (
        <button type="button" onClick={start}>
          {t('listen.listen')}
        </button>
      )}
      {state === 'playing' && (
        <button
          type="button"
          onClick={() => {
            window.speechSynthesis.pause()
            setState('paused')
          }}
        >
          {t('listen.pause')}
        </button>
      )}
      {state === 'paused' && (
        <button
          type="button"
          onClick={() => {
            window.speechSynthesis.resume()
            setState('playing')
          }}
        >
          {t('listen.resume')}
        </button>
      )}
      {state !== 'idle' && (
        <button
          type="button"
          className="secondary"
          onClick={() => {
            window.speechSynthesis.cancel()
            setState('idle')
          }}
        >
          {t('listen.stop')}
        </button>
      )}
      {summaryOnly && <span className="hint">{t('listen.summaryOnly')}</span>}
    </div>
  )
}
