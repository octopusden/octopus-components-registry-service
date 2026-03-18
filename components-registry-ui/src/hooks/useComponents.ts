import { useQuery } from '@tanstack/react-query'
import { api } from '../lib/api'
import type { ComponentSummary, ComponentFilter, Page } from '../lib/types'

interface UseComponentsParams {
  filter?: ComponentFilter
  page?: number
  size?: number
  sort?: string
}

export function useComponents({ filter, page = 0, size = 20, sort = 'name,asc' }: UseComponentsParams = {}) {
  const params = new URLSearchParams()
  params.set('page', String(page))
  params.set('size', String(size))
  params.set('sort', sort)
  if (filter?.system) params.set('system', filter.system)
  if (filter?.productType) params.set('productType', filter.productType)
  if (filter?.archived !== undefined) params.set('archived', String(filter.archived))
  if (filter?.search) params.set('search', filter.search)

  return useQuery({
    queryKey: ['components', { filter, page, size, sort }],
    queryFn: () => api.get<Page<ComponentSummary>>(`/components?${params.toString()}`),
  })
}
