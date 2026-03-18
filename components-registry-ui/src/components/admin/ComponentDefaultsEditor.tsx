import { useState, useEffect } from 'react'
import { Save, RotateCcw, Download } from 'lucide-react'
import { Button } from '../ui/button'
import { useComponentDefaults, useUpdateComponentDefaults, useMigrateDefaults } from '../../hooks/useAdminConfig'

export function ComponentDefaultsEditor() {
  const { data, isLoading, error } = useComponentDefaults()
  const updateMutation = useUpdateComponentDefaults()
  const migrateMutation = useMigrateDefaults()

  const [jsonText, setJsonText] = useState('')
  const [parseError, setParseError] = useState<string | null>(null)
  const [savedFeedback, setSavedFeedback] = useState(false)

  useEffect(() => {
    if (data !== undefined) {
      setJsonText(JSON.stringify(data, null, 2))
    }
  }, [data])

  const handleReset = () => {
    if (data !== undefined) {
      setJsonText(JSON.stringify(data, null, 2))
      setParseError(null)
    }
  }

  const handleSave = () => {
    setParseError(null)
    let parsed: Record<string, unknown>
    try {
      parsed = JSON.parse(jsonText) as Record<string, unknown>
    } catch (e) {
      setParseError(e instanceof Error ? e.message : 'Invalid JSON')
      return
    }

    updateMutation.mutate(parsed, {
      onSuccess: () => {
        setSavedFeedback(true)
        setTimeout(() => setSavedFeedback(false), 2000)
      },
    })
  }

  if (isLoading) {
    return (
      <div className="space-y-3">
        <div className="h-4 bg-muted animate-pulse rounded w-1/4" />
        <div className="h-64 bg-muted animate-pulse rounded" />
      </div>
    )
  }

  if (error) {
    return (
      <div className="rounded-md border border-destructive/50 bg-destructive/10 px-4 py-3 text-sm text-destructive">
        Failed to load component defaults:{' '}
        {error instanceof Error ? error.message : String(error)}
      </div>
    )
  }

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <p className="text-sm text-muted-foreground">
          Edit the JSON below to update component default values. Changes take effect after saving.
        </p>
        <div className="flex items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={() => migrateMutation.mutate()}
            disabled={migrateMutation.isPending || updateMutation.isPending}
            title="Import defaults from Git DSL (Defaults.groovy)"
          >
            <Download className="h-4 w-4 mr-1" />
            {migrateMutation.isPending ? 'Importing…' : 'Import from Git'}
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={handleReset}
            disabled={updateMutation.isPending}
          >
            <RotateCcw className="h-4 w-4 mr-1" />
            Reset
          </Button>
          <Button
            size="sm"
            onClick={handleSave}
            disabled={updateMutation.isPending}
          >
            <Save className="h-4 w-4 mr-1" />
            {updateMutation.isPending ? 'Saving…' : savedFeedback ? 'Saved!' : 'Save'}
          </Button>
        </div>
      </div>

      {parseError && (
        <div className="rounded-md border border-destructive/50 bg-destructive/10 px-3 py-2 text-xs text-destructive font-mono">
          JSON parse error: {parseError}
        </div>
      )}

      {updateMutation.error && (
        <div className="rounded-md border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm text-destructive">
          Save failed:{' '}
          {updateMutation.error instanceof Error
            ? updateMutation.error.message
            : String(updateMutation.error)}
        </div>
      )}

      <textarea
        className="w-full h-80 rounded-md border bg-background px-3 py-2 text-xs font-mono text-foreground focus:outline-none focus:ring-2 focus:ring-ring resize-y"
        value={jsonText}
        onChange={(e) => {
          setJsonText(e.target.value)
          setParseError(null)
        }}
        spellCheck={false}
        aria-label="Component defaults JSON"
      />
    </div>
  )
}
