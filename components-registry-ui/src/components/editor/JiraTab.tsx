import { useState, useEffect } from 'react'
import { Save } from 'lucide-react'
import { Label } from '../ui/label'
import { Input } from '../ui/input'
import { Switch } from '../ui/switch'
import { Button } from '../ui/button'
import { FieldOverrideInline } from './FieldOverrideInline'
import type { ComponentDetail } from '../../lib/types'
import type { ComponentUpdateRequest } from '../../hooks/useComponent'
import type { UseMutationResult } from '@tanstack/react-query'
import { ApiError } from '../../lib/api'

interface JiraTabProps {
  component: ComponentDetail
  updateMutation: UseMutationResult<ComponentDetail, Error, ComponentUpdateRequest>
  toast: (opts: { title: string; description?: string; variant?: 'default' | 'destructive' }) => void
}

export function JiraTab({ component, updateMutation, toast }: JiraTabProps) {
  const jira = component.jiraComponentConfigs[0]

  const [projectKey, setProjectKey] = useState(jira?.projectKey ?? '')
  const [displayName, setDisplayName] = useState(jira?.displayName ?? '')
  const [technical, setTechnical] = useState(jira?.technical ?? false)
  const [versionFormatJson, setVersionFormatJson] = useState(
    jira?.componentVersionFormat ? JSON.stringify(jira.componentVersionFormat, null, 2) : '',
  )
  const [jsonError, setJsonError] = useState<string | null>(null)

  useEffect(() => {
    const j = component.jiraComponentConfigs[0]
    setProjectKey(j?.projectKey ?? '')
    setDisplayName(j?.displayName ?? '')
    setTechnical(j?.technical ?? false)
    setVersionFormatJson(j?.componentVersionFormat ? JSON.stringify(j.componentVersionFormat, null, 2) : '')
    setJsonError(null)
  }, [component])

  async function handleSave() {
    let componentVersionFormat: Record<string, unknown> | undefined
    if (versionFormatJson.trim()) {
      try {
        componentVersionFormat = JSON.parse(versionFormatJson)
        setJsonError(null)
      } catch (e) {
        setJsonError(e instanceof Error ? e.message : 'Invalid JSON')
        return
      }
    }

    try {
      await updateMutation.mutateAsync({
        version: component.version,
        jiraComponentConfig: {
          projectKey: projectKey || undefined,
          displayName: displayName || undefined,
          technical,
          componentVersionFormat,
        },
      })
      toast({ title: 'Jira configuration saved' })
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        toast({ title: 'Conflict', description: 'Please refresh and try again.', variant: 'destructive' })
        return
      }
      toast({ title: 'Save failed', description: err instanceof Error ? err.message : String(err), variant: 'destructive' })
    }
  }

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <div className="space-y-1.5">
          <Label>Project Key</Label>
          <Input
            value={projectKey}
            onChange={(e) => setProjectKey(e.target.value)}
            placeholder="JIRA project key"
          />
          <FieldOverrideInline componentId={component.id} fieldPath="jira.projectKey" />
        </div>

        <div className="space-y-1.5">
          <Label>Display Name</Label>
          <Input
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            placeholder="Component display name in Jira"
          />
        </div>
      </div>

      <div className="flex items-center gap-3">
        <Switch
          id="jira-technical"
          checked={technical}
          onCheckedChange={setTechnical}
        />
        <Label htmlFor="jira-technical" className="cursor-pointer">Technical</Label>
      </div>

      <div className="space-y-1.5">
        <Label>Component Version Format (JSON)</Label>
        <textarea
          className="w-full h-32 rounded-md border bg-background px-3 py-2 text-xs font-mono text-foreground focus:outline-none focus:ring-2 focus:ring-ring resize-y"
          value={versionFormatJson}
          onChange={(e) => {
            setVersionFormatJson(e.target.value)
            setJsonError(null)
          }}
          spellCheck={false}
          placeholder='{"majorVersionFormat": "...", "releaseVersionFormat": "..."}'
        />
        {jsonError && (
          <p className="text-xs text-destructive">JSON error: {jsonError}</p>
        )}
      </div>

      {jira?.metadata && Object.keys(jira.metadata).length > 0 && (
        <div className="space-y-1">
          <span className="text-sm font-medium text-muted-foreground">Metadata</span>
          <pre className="rounded bg-muted px-3 py-2 text-xs overflow-auto">
            {JSON.stringify(jira.metadata, null, 2)}
          </pre>
        </div>
      )}

      <div className="flex justify-end">
        <Button size="sm" onClick={handleSave} disabled={updateMutation.isPending}>
          <Save className="h-4 w-4" />
          {updateMutation.isPending ? 'Saving...' : 'Save Jira'}
        </Button>
      </div>
    </div>
  )
}
