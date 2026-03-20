import { useState } from 'react'
import { Layout } from '../components/Layout'
import { ComponentFilters } from '../components/ComponentFilters'
import { ComponentTable } from '../components/ComponentTable'
import { Pagination } from '../components/Pagination'
import { CreateComponentButton } from '../components/CreateComponentDialog'
import { useComponents } from '../hooks/useComponents'
import type { ComponentFilter } from '../lib/types'

export function ComponentListPage() {
  const [filter, setFilter] = useState<ComponentFilter>({})
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)

  const { data, isLoading, error } = useComponents({ filter, page, size })

  const handleFilterChange = (newFilter: ComponentFilter) => {
    setFilter(newFilter)
    setPage(0) // reset to first page on filter change
  }

  const handleSizeChange = (newSize: number) => {
    setSize(newSize)
    setPage(0)
  }

  return (
    <Layout>
      <div className="space-y-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-4">
            <h1 className="text-2xl font-semibold tracking-tight">Components</h1>
            {data && (
              <span className="text-sm text-muted-foreground">
                {data.totalElements} total
              </span>
            )}
          </div>
          <CreateComponentButton />
        </div>

        <ComponentFilters filter={filter} onFilterChange={handleFilterChange} />

        {error && (
          <div className="rounded-md border border-destructive/50 bg-destructive/10 px-4 py-3 text-sm text-destructive">
            Failed to load components: {error instanceof Error ? error.message : String(error)}
          </div>
        )}

        <ComponentTable
          data={data?.content ?? []}
          isLoading={isLoading}
        />

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
