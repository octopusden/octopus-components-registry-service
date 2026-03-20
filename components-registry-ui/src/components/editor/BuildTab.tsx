import { useState, useEffect } from 'react'
import { Save } from 'lucide-react'
import { Label } from '../ui/label'
import { Input } from '../ui/input'
import { Switch } from '../ui/switch'
import { Button } from '../ui/button'
import { EnumSelect } from '../ui/EnumSelect'
import { FieldOverrideInline } from './FieldOverrideInline'
import type { ComponentDetail } from '../../lib/types'
import type { ComponentUpdateRequest } from '../../hooks/useComponent'
import type { UseMutationResult } from '@tanstack/react-query'
import { ApiError } from '../../lib/api'

interface BuildTabProps {
  component: ComponentDetail
  updateMutation: UseMutationResult<ComponentDetail, Error, ComponentUpdateRequest>
  toast: (opts: { title: string; description?: string; variant?: 'default' | 'destructive' }) => void
}

export function BuildTab({ component, updateMutation, toast }: BuildTabProps) {
  const bc = component.buildConfigurations[0]

  const [buildSystem, setBuildSystem] = useState(bc?.buildSystem ?? '')
  const [buildFilePath, setBuildFilePath] = useState(bc?.buildFilePath ?? '')
  const [javaVersion, setJavaVersion] = useState(bc?.javaVersion ?? '')
  const [deprecated, setDeprecated] = useState(bc?.deprecated ?? false)

  useEffect(() => {
    const c = component.buildConfigurations[0]
    setBuildSystem(c?.buildSystem ?? '')
    setBuildFilePath(c?.buildFilePath ?? '')
    setJavaVersion(c?.javaVersion ?? '')
    setDeprecated(c?.deprecated ?? false)
  }, [component])

  async function handleSave() {
    try {
      await updateMutation.mutateAsync({
        version: component.version,
        buildConfiguration: {
          buildSystem: buildSystem || undefined,
          buildFilePath: buildFilePath || undefined,
          javaVersion: javaVersion || undefined,
          deprecated,
        },
      })
      toast({ title: 'Build configuration saved' })
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
          <Label>Build System</Label>
          <EnumSelect
            fieldPath="buildSystem"
            value={buildSystem}
            onValueChange={setBuildSystem}
            placeholder="Select build system"
          />
          <FieldOverrideInline componentId={component.id} fieldPath="buildSystem" />
        </div>

        <div className="space-y-1.5">
          <Label>Build File Path</Label>
          <Input
            value={buildFilePath}
            onChange={(e) => setBuildFilePath(e.target.value)}
            placeholder="pom.xml / build.gradle"
          />
          <FieldOverrideInline componentId={component.id} fieldPath="buildFilePath" />
        </div>

        <div className="space-y-1.5">
          <Label>Java Version</Label>
          <Input
            value={javaVersion}
            onChange={(e) => setJavaVersion(e.target.value)}
            placeholder="1.8 / 11 / 17 / 21"
          />
        </div>
      </div>

      <div className="flex items-center gap-3">
        <Switch
          id="build-deprecated"
          checked={deprecated}
          onCheckedChange={setDeprecated}
        />
        <Label htmlFor="build-deprecated" className="cursor-pointer">Deprecated</Label>
      </div>

      {bc?.metadata && Object.keys(bc.metadata).length > 0 && (
        <div className="space-y-1">
          <span className="text-sm font-medium text-muted-foreground">Metadata</span>
          <pre className="rounded bg-muted px-3 py-2 text-xs overflow-auto">
            {JSON.stringify(bc.metadata, null, 2)}
          </pre>
        </div>
      )}

      <div className="flex justify-end">
        <Button size="sm" onClick={handleSave} disabled={updateMutation.isPending}>
          <Save className="h-4 w-4" />
          {updateMutation.isPending ? 'Saving...' : 'Save Build'}
        </Button>
      </div>
    </div>
  )
}
