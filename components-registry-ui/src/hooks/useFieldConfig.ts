import { useFieldConfig } from './useAdminConfig'

interface FieldConfigEntry {
  label?: string
  editable?: boolean
  options?: string[]
}

interface FieldConfigData {
  fields?: Record<string, FieldConfigEntry>
}

export function useFieldConfigOptions(fieldPath: string): {
  options: string[]
  isLoading: boolean
} {
  const { data, isLoading } = useFieldConfig()

  if (isLoading || !data) {
    return { options: [], isLoading }
  }

  const config = data as FieldConfigData
  const fieldEntry = config.fields?.[fieldPath]
  const options = fieldEntry?.options ?? []

  return { options, isLoading: false }
}
