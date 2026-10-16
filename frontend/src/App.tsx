import { useEffect, useState } from 'react'

type ApiStatus = 'checking' | 'up' | 'down'

function App() {
  const [apiStatus, setApiStatus] = useState<ApiStatus>('checking')

  useEffect(() => {
    const controller = new AbortController()
    fetch('/api/v1/health', { signal: controller.signal })
      .then((res) => res.json())
      .then((body: { status?: string }) => setApiStatus(body.status === 'UP' ? 'up' : 'down'))
      .catch((err: unknown) => {
        if (!(err instanceof DOMException && err.name === 'AbortError')) setApiStatus('down')
      })
    return () => controller.abort()
  }, [])

  return (
    <main>
      <h1>News Portal</h1>
      <p role="status">
        API: {apiStatus === 'checking' ? 'checking…' : apiStatus === 'up' ? 'available' : 'unavailable'}
      </p>
    </main>
  )
}

export default App
