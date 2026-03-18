import { useQuery } from '@tanstack/react-query'
import { api } from '../lib/api'

export function useOwners() {
  return useQuery({
    queryKey: ['meta', 'owners'],
    queryFn: () => api.get<string[]>('/components/meta/owners'),
    staleTime: 5 * 60 * 1000,
  })
}
