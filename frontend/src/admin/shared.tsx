import { useId, useState, type FormEvent, type ReactNode } from 'react'
import { errorText } from './hooks'

// The staff console is English-only for now (reader screens are in four languages).

export function ErrorText({ error }: { error: unknown }) {
  if (!error) return null
  return (
    <p role="alert" className="error">
      {errorText(error)}
    </p>
  )
}

export function Time({ value }: { value: string | null | undefined }) {
  if (!value) return <span className="hint">—</span>
  return <time dateTime={value}>{new Date(value).toLocaleString('en-GB', { dateStyle: 'medium', timeStyle: 'short' })}</time>
}

const BADGE_TONES: Record<string, 'good' | 'warn' | 'bad' | 'neutral'> = {
  ACTIVE: 'good',
  HEALTHY: 'good',
  PERMITTED: 'good',
  SUCCESS: 'good',
  RESOLVED: 'good',
  RECOVERING: 'warn',
  STALE: 'warn',
  PENDING: 'neutral',
  PROPOSED: 'neutral',
  UNKNOWN: 'warn',
  UNDER_REVIEW: 'warn',
  RECEIVED: 'neutral',
  RIGHTS_RESTRICTED: 'warn',
  DISPUTED: 'bad',
  DENIED: 'bad',
  FAILING: 'bad',
  FAILED: 'bad',
  SUSPENDED: 'bad',
  DISABLED: 'neutral',
  RETIRED: 'neutral',
  OPEN: 'bad',
}

/** Status shown as a word plus colour (never colour alone). */
export function Badge({ value }: { value: string | null | undefined }) {
  const text = value ?? 'NONE'
  return <span className={`badge badge-${BADGE_TONES[text] ?? 'neutral'}`}>{text.replaceAll('_', ' ').toLowerCase()}</span>
}

/**
 * A button that asks for the required notes/reason before acting. Every
 * staff change is recorded with its reason (FR-076).
 */
export function NotesAction({
  label,
  onSubmit,
  danger,
  placeholder = 'Reason (recorded in the review history and audit log)',
}: {
  label: string
  onSubmit: (notes: string) => Promise<unknown>
  danger?: boolean
  placeholder?: string
}) {
  const [open, setOpen] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const id = useId()

  if (!open) {
    return (
      <button type="button" className={danger ? 'danger' : 'secondary'} onClick={() => setOpen(true)}>
        {label}
      </button>
    )
  }

  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    const notes = String(new FormData(e.currentTarget).get('notes') ?? '').trim()
    try {
      await onSubmit(notes)
      setOpen(false)
      setError(null)
    } catch (err) {
      setError(err)
    }
  }

  return (
    <form onSubmit={submit} className="notes-action">
      <label htmlFor={id} className="visually-hidden">
        {placeholder}
      </label>
      <textarea id={id} name="notes" required rows={2} placeholder={placeholder} autoFocus />
      <div className="actions">
        <button type="submit" className={danger ? 'danger' : undefined}>
          {label}
        </button>
        <button type="button" className="secondary" onClick={() => setOpen(false)}>
          Cancel
        </button>
      </div>
      <ErrorText error={error} />
    </form>
  )
}

export function Section({ title, children }: { title: string; children: ReactNode }) {
  const id = useId()
  return (
    <section aria-labelledby={id} className="admin-section">
      <h2 id={id}>{title}</h2>
      {children}
    </section>
  )
}

