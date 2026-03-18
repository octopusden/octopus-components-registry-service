import { useQuery } from '@tanstack/react-query'
import { api } from '../lib/api'
import type { AuditLogEntry, Page } from '../lib/types'

interface UseAuditLogParams {
  page?: number
  size?: number
}

export function useRecentAuditLog({ page = 0, size = 20 }: UseAuditLogParams = {}) {
  const params = new URLSearchParams()
  params.set('page', String(page))
  params.set('size', String(size))
  params.set('sort', 'changedAt,desc')

  return useQuery({
    queryKey: ['audit', 'recent', { page, size }],
    queryFn: () => api.get<Page<AuditLogEntry>>(`/audit/recent?${params.toString()}`),
  })
}

export function useEntityAuditLog(
  entityType: string,
  entityId: string,
  { page = 0, size = 20 }: UseAuditLogParams = {}
) {
  const params = new URLSearchParams()
  params.set('page', String(page))
  params.set('size', String(size))
  params.set('sort', 'changedAt,desc')

  return useQuery({
    queryKey: ['audit', entityType, entityId, { page, size }],
    queryFn: () =>
      api.get<Page<AuditLogEntry>>(`/audit/${entityType}/${entityId}?${params.toString()}`),
    enabled: !!entityType && !!entityId,
  })
}
