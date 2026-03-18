import { describe, it, expect, vi, afterEach } from 'vitest'
import { api, ApiError } from './api'

afterEach(() => {
  vi.restoreAllMocks()
})

describe('api — URL construction', () => {
  it('prefixes requests with BASE_URL so the gateway sub-path is included', async () => {
    // Regression guard: if API_BASE is hardcoded to '/rest/api/4' instead of
    // using import.meta.env.BASE_URL, API calls go to /rest/api/4/... and the
    // gateway (which serves the app at /components-registry-service/) returns 404.
    const mockFetch = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ content: [] }), { status: 200 }),
    )
    vi.stubGlobal('fetch', mockFetch)

    await api.get('/components')

    const calledUrl = mockFetch.mock.calls[0]![0] as string
    expect(calledUrl).toBe(`${import.meta.env.BASE_URL}rest/api/4/components`)
    // Must NOT be the root-absolute path that bypasses the gateway prefix
    expect(calledUrl).not.toBe('/rest/api/4/components')
  })

  it('throws ApiError with status on non-ok response', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response('Not Found', { status: 404 })),
    )

    const error = await api.get('/components').catch((e) => e) as ApiError
    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBe(404)
  })
})
