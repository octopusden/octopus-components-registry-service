import { useState, useEffect } from 'react'
import { Save, Plus, Trash2 } from 'lucide-react'
import { Label } from '../ui/label'
import { Input } from '../ui/input'
import { Button } from '../ui/button'
import { EnumSelect } from '../ui/EnumSelect'
import { Separator } from '../ui/separator'
import type { ComponentDetail, VcsSettingsEntry } from '../../lib/types'
import type { ComponentUpdateRequest, VcsSettingsEntryUpdate } from '../../hooks/useComponent'
import type { UseMutationResult } from '@tanstack/react-query'
import { ApiError } from '../../lib/api'

interface VcsTabProps {
  component: ComponentDetail
  updateMutation: UseMutationResult<ComponentDetail, Error, ComponentUpdateRequest>
  toast: (opts: { title: string; description?: string; variant?: 'default' | 'destructive' }) => void
}

interface EntryState {
  id?: string | null
  name: string
  vcsPath: string
  repositoryType: string
  tag: string
  branch: string
}

function toEntryState(e: VcsSettingsEntry): EntryState {
  return {
    id: e.id,
    name: e.name ?? '',
    vcsPath: e.vcsPath ?? '',
    repositoryType: e.repositoryType ?? 'GIT',
    tag: e.tag ?? '',
    branch: e.branch ?? '',
  }
}

export function VcsTab({ component, updateMutation, toast }: VcsTabProps) {
  const vcs = component.vcsSettings[0]

  const [vcsType, setVcsType] = useState(vcs?.vcsType ?? 'SINGLE')
  const [externalRegistry, setExternalRegistry] = useState(vcs?.externalRegistry ?? '')
  const [entries, setEntries] = useState<EntryState[]>(vcs?.entries?.map(toEntryState) ?? [])

  useEffect(() => {
    const v = component.vcsSettings[0]
    setVcsType(v?.vcsType ?? 'SINGLE')
    setExternalRegistry(v?.externalRegistry ?? '')
    setEntries(v?.entries?.map(toEntryState) ?? [])
  }, [component])

  function updateEntry(index: number, field: keyof EntryState, value: string) {
    setEntries((prev) => prev.map((e, i) => (i === index ? { ...e, [field]: value } : e)))
  }

  function addEntry() {
    setEntries((prev) => [...prev, { name: '', vcsPath: '', repositoryType: 'GIT', tag: '', branch: '' }])
  }

  function removeEntry(index: number) {
    setEntries((prev) => prev.filter((_, i) => i !== index))
  }

  async function handleSave() {
    const entryUpdates: VcsSettingsEntryUpdate[] = entries.map((e) => ({
      id: e.id ?? undefined,
      name: e.name || undefined,
      vcsPath: e.vcsPath || undefined,
      repositoryType: e.repositoryType || 'GIT',
      tag: e.tag || undefined,
      branch: e.branch || undefined,
    }))

    try {
      await updateMutation.mutateAsync({
        version: component.version,
        vcsSettings: {
          vcsType: vcsType || undefined,
          externalRegistry: externalRegistry || undefined,
          entries: entryUpdates,
        },
      })
      toast({ title: 'VCS settings saved' })
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
          <Label>VCS Type</Label>
          <EnumSelect
            fieldPath="vcsType"
            value={vcsType}
            onValueChange={setVcsType}
            placeholder="Select VCS type"
            allowFreeText
          />
        </div>

        <div className="space-y-1.5">
          <Label>External Registry</Label>
          <Input
            value={externalRegistry}
            onChange={(e) => setExternalRegistry(e.target.value)}
            placeholder="External registry URL"
          />
        </div>
      </div>

      <Separator />

      <div className="space-y-3">
        <div className="flex items-center justify-between">
          <h3 className="text-sm font-semibold">VCS Entries</h3>
          <Button variant="outline" size="sm" onClick={addEntry}>
            <Plus className="h-4 w-4" />
            Add Entry
          </Button>
        </div>

        {entries.map((entry, index) => (
          <div key={index} className="rounded-md border p-3 space-y-3">
            <div className="flex items-center justify-between">
              <span className="text-xs font-medium text-muted-foreground">Entry {index + 1}</span>
              <Button variant="ghost" size="sm" onClick={() => removeEntry(index)} className="h-7 text-destructive hover:text-destructive">
                <Trash2 className="h-3 w-3" />
              </Button>
            </div>
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
              <div className="space-y-1">
                <Label className="text-xs">Name</Label>
                <Input value={entry.name} onChange={(e) => updateEntry(index, 'name', e.target.value)} placeholder="Entry name" />
              </div>
              <div className="space-y-1">
                <Label className="text-xs">VCS Path</Label>
                <Input value={entry.vcsPath} onChange={(e) => updateEntry(index, 'vcsPath', e.target.value)} placeholder="ssh://git@..." className="font-mono text-xs" />
              </div>
              <div className="space-y-1">
                <Label className="text-xs">Repository Type</Label>
                <EnumSelect fieldPath="repositoryType" value={entry.repositoryType} onValueChange={(val) => updateEntry(index, 'repositoryType', val)} placeholder="GIT" />
              </div>
              <div className="space-y-1">
                <Label className="text-xs">Tag</Label>
                <Input value={entry.tag} onChange={(e) => updateEntry(index, 'tag', e.target.value)} placeholder="Tag pattern" className="font-mono text-xs" />
              </div>
              <div className="space-y-1">
                <Label className="text-xs">Branch</Label>
                <Input value={entry.branch} onChange={(e) => updateEntry(index, 'branch', e.target.value)} placeholder="Branch pattern" className="font-mono text-xs" />
              </div>
            </div>
          </div>
        ))}

        {entries.length === 0 && (
          <div className="rounded-md border border-dashed p-6 text-center text-sm text-muted-foreground">
            No VCS entries. Click "Add Entry" to create one.
          </div>
        )}
      </div>

      <div className="flex justify-end">
        <Button size="sm" onClick={handleSave} disabled={updateMutation.isPending}>
          <Save className="h-4 w-4" />
          {updateMutation.isPending ? 'Saving...' : 'Save VCS'}
        </Button>
      </div>
    </div>
  )
}
