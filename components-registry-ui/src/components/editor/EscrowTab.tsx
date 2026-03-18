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

interface EscrowTabProps {
  component: ComponentDetail
  updateMutation: UseMutationResult<ComponentDetail, Error, ComponentUpdateRequest>
  toast: (opts: { title: string; description?: string; variant?: 'default' | 'destructive' }) => void
}

export function EscrowTab({ component, updateMutation, toast }: EscrowTabProps) {
  const esc = component.escrowConfigurations[0]

  const [buildTask, setBuildTask] = useState(esc?.buildTask ?? '')
  const [generation, setGeneration] = useState(esc?.generation ?? '')
  const [diskSpace, setDiskSpace] = useState(esc?.diskSpace ?? '')
  const [reusable, setReusable] = useState(esc?.reusable ?? false)
  const [providedDependencies, setProvidedDependencies] = useState(esc?.providedDependencies ?? '')

  useEffect(() => {
    const e = component.escrowConfigurations[0]
    setBuildTask(e?.buildTask ?? '')
    setGeneration(e?.generation ?? '')
    setDiskSpace(e?.diskSpace ?? '')
    setReusable(e?.reusable ?? false)
    setProvidedDependencies(e?.providedDependencies ?? '')
  }, [component])

  async function handleSave() {
    try {
      await updateMutation.mutateAsync({
        version: component.version,
        escrowConfiguration: {
          buildTask: buildTask || undefined,
          generation: generation || undefined,
          diskSpace: diskSpace || undefined,
          reusable,
          providedDependencies: providedDependencies || undefined,
        },
      })
      toast({ title: 'Escrow configuration saved' })
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
          <Label>Build Task</Label>
          <Input
            value={buildTask}
            onChange={(e) => setBuildTask(e.target.value)}
            placeholder="clean install"
          />
          <FieldOverrideInline componentId={component.id} fieldPath="escrow.buildTask" />
        </div>

        <div className="space-y-1.5">
          <Label>Generation</Label>
          <EnumSelect
            fieldPath="generation"
            value={generation}
            onValueChange={setGeneration}
            placeholder="Select generation"
          />
        </div>

        <div className="space-y-1.5">
          <Label>Disk Space</Label>
          <Input
            value={diskSpace}
            onChange={(e) => setDiskSpace(e.target.value)}
            placeholder="e.g. 10GB"
          />
        </div>
      </div>

      <div className="flex items-center gap-3">
        <Switch
          id="escrow-reusable"
          checked={reusable}
          onCheckedChange={setReusable}
        />
        <Label htmlFor="escrow-reusable" className="cursor-pointer">Reusable</Label>
      </div>

      <div className="space-y-1.5">
        <Label>Provided Dependencies</Label>
        <textarea
          className="w-full h-24 rounded-md border bg-background px-3 py-2 text-xs font-mono text-foreground focus:outline-none focus:ring-2 focus:ring-ring resize-y"
          value={providedDependencies}
          onChange={(e) => setProvidedDependencies(e.target.value)}
          spellCheck={false}
          placeholder="Comma-separated list of provided dependencies"
        />
      </div>

      <div className="flex justify-end">
        <Button size="sm" onClick={handleSave} disabled={updateMutation.isPending}>
          <Save className="h-4 w-4" />
          {updateMutation.isPending ? 'Saving...' : 'Save Escrow'}
        </Button>
      </div>
    </div>
  )
}
