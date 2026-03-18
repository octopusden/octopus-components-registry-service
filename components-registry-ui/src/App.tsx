import { BrowserRouter, Routes, Route, Navigate } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ComponentListPage } from './pages/ComponentListPage'
import { ComponentDetailPage } from './pages/ComponentDetailPage'
import { AuditLogPage } from './pages/AuditLogPage'
import { AdminSettingsPage } from './pages/AdminSettingsPage'
import { Toaster } from './components/ui/toaster'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: 1,
    },
  },
})


export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter basename={`${import.meta.env.BASE_URL}ui`}>
        <Routes>
          <Route path="/" element={<Navigate to="/components" replace />} />
          <Route path="/components" element={<ComponentListPage />} />
          <Route path="/components/:id" element={<ComponentDetailPage />} />
          <Route path="/audit" element={<AuditLogPage />} />
          <Route path="/admin" element={<AdminSettingsPage />} />
        </Routes>
        <Toaster />
      </BrowserRouter>
    </QueryClientProvider>
  )
}
