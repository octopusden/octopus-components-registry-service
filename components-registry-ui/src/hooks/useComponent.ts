import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from '../lib/api'
import type { ComponentDetail, FieldOverride } from '../lib/types'

export interface ComponentCreateRequest {
  name: string
  displayName?: string
  componentOwner?: string
  productType?: string
  system?: string[]
  clientCode?: string
  solution?: boolean
  archived?: boolean
  metadata?: Record<string, unknown>
}

export interface BuildConfigurationUpdate {
  buildSystem?: string
  buildFilePath?: string
  javaVersion?: string
  deprecated?: boolean
  metadata?: Record<string, unknown>
}

export interface VcsSettingsEntryUpdate {
  id?: string
  name?: string
  vcsPath?: string
  repositoryType?: string
  tag?: string
  branch?: string
}

export interface VcsSettingsUpdate {
  vcsType?: string
  externalRegistry?: string
  entries?: VcsSettingsEntryUpdate[]
}

export interface DistributionArtifactUpdate {
  id?: string
  artifactType?: string
  groupPattern?: string
  artifactPattern?: string
  name?: string
  tag?: string
}

export interface DistributionSecurityGroupUpdate {
  id?: string
  groupType?: string
  groupName?: string
}

export interface DistributionUpdate {
  explicit?: boolean
  external?: boolean
  artifacts?: DistributionArtifactUpdate[]
  securityGroups?: DistributionSecurityGroupUpdate[]
}

export interface JiraComponentConfigUpdate {
  projectKey?: string
  displayName?: string
  componentVersionFormat?: Record<string, unknown>
  technical?: boolean
  metadata?: Record<string, unknown>
}

export interface EscrowConfigurationUpdate {
  buildTask?: string
  providedDependencies?: string
  reusable?: boolean
  generation?: string
  diskSpace?: string
}

export interface ComponentUpdateRequest {
  version: number
  displayName?: string
  componentOwner?: string
  productType?: string
  system?: string[]
  clientCode?: string
  solution?: boolean
  archived?: boolean
  metadata?: Record<string, unknown>
  buildConfiguration?: BuildConfigurationUpdate
  vcsSettings?: VcsSettingsUpdate
  distribution?: DistributionUpdate
  jiraComponentConfig?: JiraComponentConfigUpdate
  escrowConfiguration?: EscrowConfigurationUpdate
}

export function useComponent(id: string) {
  return useQuery({
    queryKey: ['component', id],
    queryFn: () => api.get<ComponentDetail>(`/components/${id}`),
    enabled: !!id,
  })
}

export function useCreateComponent() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: ComponentCreateRequest) =>
      api.post<ComponentDetail>('/components', request),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['components'] }),
  })
}

export function useUpdateComponent(id: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: ComponentUpdateRequest) =>
      api.patch<ComponentDetail>(`/components/${id}`, request),
    onSuccess: (data) => {
      queryClient.setQueryData(['component', id], data)
      queryClient.invalidateQueries({ queryKey: ['components'] })
    },
  })
}

export function useDeleteComponent(id: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => api.delete(`/components/${id}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['components'] }),
  })
}

export function useFieldOverrides(componentId: string) {
  return useQuery({
    queryKey: ['field-overrides', componentId],
    queryFn: () => api.get<FieldOverride[]>(`/components/${componentId}/field-overrides`),
    enabled: !!componentId,
  })
}

export function useCreateFieldOverride(componentId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: { fieldPath: string; versionRange: string; value: unknown }) =>
      api.post<FieldOverride>(`/components/${componentId}/field-overrides`, request),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: ['field-overrides', componentId] }),
  })
}

export function useUpdateFieldOverride(componentId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({
      overrideId,
      ...request
    }: {
      overrideId: string
      fieldPath?: string
      versionRange?: string
      value?: unknown
    }) =>
      api.patch<FieldOverride>(
        `/components/${componentId}/field-overrides/${overrideId}`,
        request
      ),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: ['field-overrides', componentId] }),
  })
}

export function useDeleteFieldOverride(componentId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (overrideId: string) =>
      api.delete(`/components/${componentId}/field-overrides/${overrideId}`),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: ['field-overrides', componentId] }),
  })
}
