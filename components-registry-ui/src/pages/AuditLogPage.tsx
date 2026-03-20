import { useState } from 'react'
import { Layout } from '../components/Layout'
import { AuditLogTable } from '../components/AuditLogTable'
import { Pagination } from '../components/Pagination'
import { useRecentAuditLog } from '../hooks/useAuditLog'

export function AuditLogPage() {
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)

  const { data, isLoading, error } = useRecentAuditLog({ page, size })

  const handleSizeChange = (newSize: number) => {
    setSize(newSize)
    setPage(0)
  }

  return (
    <Layout>
      <div className="space-y-4">
        <div className="flex items-center justify-between">
          <h1 className="text-2xl font-semibold tracking-tight">Audit Log</h1>
          {data && (
            <span className="text-sm text-muted-foreground">
              {data.totalElements} total entries
            </span>
          )}
        </div>

        {error && (
          <div className="rounded-md border border-destructive/50 bg-destructive/10 px-4 py-3 text-sm text-destructive">
            Failed to load audit log:{' '}
            {error instanceof Error ? error.message : String(error)}
          </div>
        )}

        <AuditLogTable data={data?.content ?? []} isLoading={isLoading} />

        {data && data.totalElements > 0 && (
          <Pagination
            page={page}
            totalPages={data.totalPages}
            totalElements={data.totalElements}
            size={size}
            onPageChange={setPage}
            onSizeChange={handleSizeChange}
          />
        )}
      </div>
    </Layout>
  )
}
