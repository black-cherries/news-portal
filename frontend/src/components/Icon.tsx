// Small inline icons (no icon font or external requests). Decorative: the
// surrounding text or label carries the meaning.

const PATHS = {
  search: 'M10.5 3a7.5 7.5 0 0 1 5.96 12.05l4.25 4.24-1.42 1.42-4.24-4.25A7.5 7.5 0 1 1 10.5 3Zm0 2a5.5 5.5 0 1 0 0 11 5.5 5.5 0 0 0 0-11Z',
  globe:
    'M12 2a10 10 0 1 1 0 20 10 10 0 0 1 0-20Zm2.9 11H9.1c.2 3.1 1.4 5.6 2.9 6.9 1.5-1.3 2.7-3.8 2.9-6.9ZM7.1 13H4.06A8 8 0 0 0 9.3 19.5 13 13 0 0 1 7.1 13Zm12.84 0H16.9a13 13 0 0 1-2.2 6.5 8 8 0 0 0 5.24-6.5ZM9.3 4.5A8 8 0 0 0 4.06 11H7.1a13 13 0 0 1 2.2-6.5Zm2.7-.4c-1.5 1.3-2.7 3.8-2.9 6.9h5.8c-.2-3.1-1.4-5.6-2.9-6.9Zm2.7.4a13 13 0 0 1 2.2 6.5h3.04A8 8 0 0 0 14.7 4.5Z',
  home: 'M12 3 2.5 11h2.5v9h5.5v-6h3v6H19v-9h2.5L12 3Z',
  spark: 'M12 2l2.4 6.6L21 11l-6.6 2.4L12 20l-2.4-6.6L3 11l6.6-2.4L12 2Z',
  bookmark: 'M6 3h12v18l-6-4-6 4V3Zm2 2v12.26l4-2.67 4 2.67V5H8Z',
  download: 'M11 3h2v9.17l3.59-3.58L18 10l-6 6-6-6 1.41-1.41L11 12.17V3ZM5 18h14v2H5v-2Z',
  user: 'M12 3a4.5 4.5 0 1 1 0 9 4.5 4.5 0 0 1 0-9Zm0 2a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5Zm0 9c4.4 0 8 2.2 8 5v2H4v-2c0-2.8 3.6-5 8-5Zm0 2c-3.3 0-5.6 1.5-6 3h12c-.4-1.5-2.7-3-6-3Z',
  filter: 'M3 5h18v2l-7 7v5l-4 2v-7L3 7V5Z',
} as const

export type IconName = keyof typeof PATHS

export function Icon({ name, size = 20 }: { name: IconName; size?: number }) {
  return (
    <svg className="icon" width={size} height={size} viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <path fill="currentColor" fillRule="evenodd" d={PATHS[name]} />
    </svg>
  )
}
