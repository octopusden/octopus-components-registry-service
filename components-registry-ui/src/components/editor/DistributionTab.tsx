import { useState, useEffect } from 'react'
import { Save, Plus, Trash2 } from 'lucide-react'
import { Label } from '../ui/label'
import { Input } from '../ui/input'
import { Switch } from '../ui/switch'
import { Button } from '../ui/button'
import { Separator } from '../ui/separator'
import type { ComponentDetail, DistributionArtifact, DistributionSecurityGroup } from '../../lib/types'
import type { ComponentUpdateRequest } from '../../hooks/useComponent'
import type { UseMutationResult } from '@tanstack/react-query'
import { ApiError } from '../../lib/api'

interface DistributionTabProps {
  component: ComponentDetail
  updateMutation: UseMutationResult<ComponentDetail, Error, ComponentUpdateRequest>
  toast: (opts: { title: string; description?: string; variant?: 'default' | 'destructive' }) => void
}

interface ArtifactState {
  id?: string | null
  artifactType: string
  groupPattern: string
  artifactPattern: string
  name: string
  tag: string
}

interface SecurityGroupState {
  id?: string | null
  groupType: string
  groupName: string
}

export function DistributionTab({ component, updateMutation, toast }: DistributionTabProps) {
  const dist = component.distributions[0]

  const [explicit, setExplicit] = useState(dist?.explicit ?? false)
  const [external, setExternal] = useState(dist?.external ?? false)
  const [artifacts, setArtifacts] = useState<ArtifactState[]>(
    dist?.artifacts?.map((a: DistributionArtifact) => ({
      id: a.id, artifactType: a.artifactType ?? '', groupPattern: a.groupPattern ?? '',
      artifactPattern: a.artifactPattern ?? '', name: a.name ?? '', tag: a.tag ?? '',
    })) ?? [],
  )
  const [securityGroups, setSecurityGroups] = useState<SecurityGroupState[]>(
    dist?.securityGroups?.map((g: DistributionSecurityGroup) => ({
      id: g.id, groupType: g.groupType ?? 'read', groupName: g.groupName ?? '',
    })) ?? [],
  )

  useEffect(() => {
    const d = component.distributions[0]
    setExplicit(d?.explicit ?? false)
    setExternal(d?.external ?? false)
    setArtifacts(d?.artifacts?.map((a: DistributionArtifact) => ({
      id: a.id, artifactType: a.artifactType ?? '', groupPattern: a.groupPattern ?? '',
      artifactPattern: a.artifactPattern ?? '', name: a.name ?? '', tag: a.tag ?? '',
    })) ?? [])
    setSecurityGroups(d?.securityGroups?.map((g: DistributionSecurityGroup) => ({
      id: g.id, groupType: g.groupType ?? 'read', groupName: g.groupName ?? '',
    })) ?? [])
  }, [component])

  async function handleSave() {
    try {
      await updateMutation.mutateAsync({
        version: component.version,
        distribution: {
          explicit,
          external,
          artifacts: artifacts.map((a) => ({
            id: a.id ?? undefined,
            artifactType: a.artifactType || undefined,
            groupPattern: a.groupPattern || undefined,
            artifactPattern: a.artifactPattern || undefined,
            name: a.name || undefined,
            tag: a.tag || undefined,
          })),
          securityGroups: securityGroups.map((g) => ({
            id: g.id ?? undefined,
            groupType: g.groupType || 'read',
            groupName: g.groupName || undefined,
          })),
        },
      })
      toast({ title: 'Distribution saved' })
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
      <div className="flex flex-wrap gap-6">
        <div className="flex items-center gap-3">
          <Switch id="dist-explicit" checked={explicit} onCheckedChange={setExplicit} />
          <Label htmlFor="dist-explicit" className="cursor-pointer">Explicit</Label>
        </div>
        <div className="flex items-center gap-3">
          <Switch id="dist-external" checked={external} onCheckedChange={setExternal} />
          <Label htmlFor="dist-external" className="cursor-pointer">External</Label>
        </div>
      </div>

      <Separator />

      {/* Artifacts */}
      <div className="space-y-3">
        <div className="flex items-center justify-between">
          <h3 className="text-sm font-semibold">Artifacts</h3>
          <Button variant="outline" size="sm" onClick={() => setArtifacts((prev) => [...prev, { artifactType: '', groupPattern: '', artifactPattern: '', name: '', tag: '' }])}>
            <Plus className="h-4 w-4" /> Add Artifact
          </Button>
        </div>
        {artifacts.map((art, index) => (
          <div key={index} className="rounded-md border p-3 space-y-3">
            <div className="flex items-center justify-between">
              <span className="text-xs font-medium text-muted-foreground">Artifact {index + 1}</span>
              <Button variant="ghost" size="sm" onClick={() => setArtifacts((prev) => prev.filter((_, i) => i !== index))} className="h-7 text-destructive hover:text-destructive">
                <Trash2 className="h-3 w-3" />
              </Button>
            </div>
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
              <div className="space-y-1">
                <Label className="text-xs">Type</Label>
                <Input value={art.artifactType} onChange={(e) => setArtifacts((prev) => prev.map((a, i) => i === index ? { ...a, artifactType: e.target.value } : a))} placeholder="GAV / DEB / RPM / docker" />
              </div>
              <div className="space-y-1">
                <Label className="text-xs">Name</Label>
                <Input value={art.name} onChange={(e) => setArtifacts((prev) => prev.map((a, i) => i === index ? { ...a, name: e.target.value } : a))} />
              </div>
              <div className="space-y-1">
                <Label className="text-xs">Group Pattern</Label>
                <Input value={art.groupPattern} onChange={(e) => setArtifacts((prev) => prev.map((a, i) => i === index ? { ...a, groupPattern: e.target.value } : a))} className="font-mono text-xs" />
              </div>
              <div className="space-y-1">
                <Label className="text-xs">Artifact Pattern</Label>
                <Input value={art.artifactPattern} onChange={(e) => setArtifacts((prev) => prev.map((a, i) => i === index ? { ...a, artifactPattern: e.target.value } : a))} className="font-mono text-xs" />
              </div>
              <div className="space-y-1">
                <Label className="text-xs">Tag</Label>
                <Input value={art.tag} onChange={(e) => setArtifacts((prev) => prev.map((a, i) => i === index ? { ...a, tag: e.target.value } : a))} />
              </div>
            </div>
          </div>
        ))}
        {artifacts.length === 0 && (
          <div className="rounded-md border border-dashed p-4 text-center text-sm text-muted-foreground">No artifacts.</div>
        )}
      </div>

      <Separator />

      {/* Security Groups */}
      <div className="space-y-3">
        <div className="flex items-center justify-between">
          <h3 className="text-sm font-semibold">Security Groups</h3>
          <Button variant="outline" size="sm" onClick={() => setSecurityGroups((prev) => [...prev, { groupType: 'read', groupName: '' }])}>
            <Plus className="h-4 w-4" /> Add Group
          </Button>
        </div>
        {securityGroups.map((group, index) => (
          <div key={index} className="flex items-center gap-3">
            <Input value={group.groupType} onChange={(e) => setSecurityGroups((prev) => prev.map((g, i) => i === index ? { ...g, groupType: e.target.value } : g))} className="w-24" placeholder="read" />
            <Input value={group.groupName} onChange={(e) => setSecurityGroups((prev) => prev.map((g, i) => i === index ? { ...g, groupName: e.target.value } : g))} placeholder="Group name" className="flex-1" />
            <Button variant="ghost" size="sm" onClick={() => setSecurityGroups((prev) => prev.filter((_, i) => i !== index))} className="text-destructive hover:text-destructive">
              <Trash2 className="h-3 w-3" />
            </Button>
          </div>
        ))}
        {securityGroups.length === 0 && (
          <div className="rounded-md border border-dashed p-4 text-center text-sm text-muted-foreground">No security groups.</div>
        )}
      </div>

      <div className="flex justify-end">
        <Button size="sm" onClick={handleSave} disabled={updateMutation.isPending}>
          <Save className="h-4 w-4" />
          {updateMutation.isPending ? 'Saving...' : 'Save Distribution'}
        </Button>
      </div>
    </div>
  )
}
