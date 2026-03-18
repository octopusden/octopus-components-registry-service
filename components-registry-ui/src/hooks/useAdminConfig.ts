import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from '../lib/api'

export function useFieldConfig() {
  return useQuery({
    queryKey: ['config', 'field-config'],
    queryFn: () => api.get<Record<string, unknown>>('/config/field-config'),
  })
}

export function useUpdateFieldConfig() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (value: Record<string, unknown>) =>
      api.put<Record<string, unknown>>('/admin/config/field-config', value),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['config', 'field-config'] }),
  })
}

export function useComponentDefaults() {
  return useQuery({
    queryKey: ['config', 'component-defaults'],
    queryFn: () => api.get<Record<string, unknown>>('/config/component-defaults'),
  })
}

export function useUpdateComponentDefaults() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (value: Record<string, unknown>) =>
      api.put<Record<string, unknown>>('/admin/config/component-defaults', value),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['config', 'component-defaults'] }),
  })
}

export function useMigrateDefaults() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => api.post<Record<string, unknown>>('/admin/migrate-defaults'),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['config', 'component-defaults'] }),
  })
}
