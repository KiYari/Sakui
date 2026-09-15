import { useEffect, useState } from 'react'

type HealthResponse = { status: string }

function App() {
  const [health, setHealth] = useState<string>('checking...')

  useEffect(() => {
    fetch('/api/health')
      .then((res) => res.json() as Promise<HealthResponse>)
      .then((data) => setHealth(data.status))
      .catch(() => setHealth('unreachable'))
  }, [])

  return (
    <div>
      <h1>eeck</h1>
      <p>API health: {health}</p>
    </div>
  )
}

export default App
