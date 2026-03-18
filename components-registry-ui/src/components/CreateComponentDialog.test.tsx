import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { CreateComponentButton } from './CreateComponentDialog'

vi.mock('../hooks/useComponent', () => ({
  useCreateComponent: vi.fn(() => ({
    mutateAsync: vi.fn(),
    isPending: false,
  })),
}))

function renderWithProviders(ui: React.ReactElement) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('CreateComponentButton', () => {
  it('opens the dialog without crashing when New Component is clicked', async () => {
    renderWithProviders(<CreateComponentButton />)

    await userEvent.click(screen.getByRole('button', { name: /new component/i }))

    expect(screen.getByRole('dialog')).toBeDefined()
    expect(screen.getByText('Create Component')).toBeDefined()
  })

  it('renders all form fields inside the dialog', async () => {
    renderWithProviders(<CreateComponentButton />)

    await userEvent.click(screen.getByRole('button', { name: /new component/i }))

    expect(screen.getByPlaceholderText('my-component')).toBeDefined()
    expect(screen.getByPlaceholderText('My Component')).toBeDefined()
    expect(screen.getByPlaceholderText('owner@example.com')).toBeDefined()
    expect(screen.getByLabelText(/product type/i)).toBeDefined()
  })
})
