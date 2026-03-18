import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { App } from './App'

// Prevent real API calls from the components list page
vi.mock('./hooks/useComponents', () => ({
  useComponents: vi.fn(() => ({ data: undefined, isLoading: true, error: null })),
}))

beforeEach(() => {
  vi.clearAllMocks()
})

describe('App routing', () => {
  it('renders the app shell when navigated to the components path under BASE_URL', () => {
    // Regression guard: BrowserRouter basename must be derived from import.meta.env.BASE_URL
    // so that deployments under a sub-path (e.g. /components-registry-service/) work.
    //
    // If basename is hardcoded to '/ui' and BASE_URL changes, the router fails to match
    // any route and the page is blank.
    const basePath = `${import.meta.env.BASE_URL}ui`
    window.history.pushState({}, '', `${basePath}/components`)

    render(<App />)

    // Layout always renders the app title; if routing is broken, nothing renders
    expect(screen.getByText('Components Registry')).toBeDefined()
  })

  it('redirects from the bare base path to /components', () => {
    const basePath = `${import.meta.env.BASE_URL}ui`
    window.history.pushState({}, '', basePath)

    render(<App />)

    expect(screen.getByText('Components Registry')).toBeDefined()
  })
})
