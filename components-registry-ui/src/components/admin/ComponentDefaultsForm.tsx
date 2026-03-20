import { useState, useEffect, useCallback } from 'react'
import { Save, RotateCcw, Download, Code } from 'lucide-react'
import { Label } from '../ui/label'
import { Input } from '../ui/input'
import { Switch } from '../ui/switch'
import { Button } from '../ui/button'
import { Separator } from '../ui/separator'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '../ui/tabs'
import { EnumSelect } from '../ui/EnumSelect'
import { useComponentDefaults, useUpdateComponentDefaults, useMigrateDefaults } from '../../hooks/useAdminConfig'

interface DefaultsData {
  // General
  buildSystem?: string
  buildFilePath?: string
  artifactIdPattern?: string
  groupIdPattern?: string
  componentDisplayName?: string
  componentOwner?: string
  releaseManager?: string
  securityChampion?: string
  system?: string
  clientCode?: string
  parentComponent?: string
  releasesInDefaultBranch?: boolean
  solution?: boolean
  archived?: boolean
  deprecated?: boolean
  copyright?: string
  octopusVersion?: string
  labels?: string[]
  // Nested
  build?: Record<string, unknown>
  jira?: Record<string, unknown>
  distribution?: Record<string, unknown>
  vcs?: Record<string, unknown>
  escrow?: Record<string, unknown>
  doc?: Record<string, unknown>
  [key: string]: unknown
}

function getStr(obj: Record<string, unknown> | undefined, key: string): string {
  return (obj?.[key] as string) ?? ''
}

function getBool(obj: Record<string, unknown> | undefined, key: string): boolean {
  return (obj?.[key] as boolean) ?? false
}

export function ComponentDefaultsForm() {
  const { data, isLoading, error } = useComponentDefaults()
  const updateMutation = useUpdateComponentDefaults()
  const migrateMutation = useMigrateDefaults()

  const [defaults, setDefaults] = useState<DefaultsData>({})
  const [showRawJson, setShowRawJson] = useState(false)
  const [jsonText, setJsonText] = useState('')
  const [parseError, setParseError] = useState<string | null>(null)
  const [savedFeedback, setSavedFeedback] = useState(false)

  useEffect(() => {
    if (data) {
      setDefaults(data as DefaultsData)
      setJsonText(JSON.stringify(data, null, 2))
    }
  }, [data])

  const setField = useCallback((key: string, value: unknown) => {
    setDefaults((prev) => ({ ...prev, [key]: value }))
  }, [])

  const setNested = useCallback((section: string, key: string, value: unknown) => {
    setDefaults((prev) => ({
      ...prev,
      [section]: { ...(prev[section] as Record<string, unknown> ?? {}), [key]: value },
    }))
  }, [])

  const handleReset = () => {
    if (data) {
      setDefaults(data as DefaultsData)
      setJsonText(JSON.stringify(data, null, 2))
      setParseError(null)
    }
  }

  const handleSave = () => {
    let toSave: Record<string, unknown>

    if (showRawJson) {
      try {
        toSave = JSON.parse(jsonText)
        setParseError(null)
      } catch (e) {
        setParseError(e instanceof Error ? e.message : 'Invalid JSON')
        return
      }
    } else {
      toSave = defaults as Record<string, unknown>
    }

    updateMutation.mutate(toSave, {
      onSuccess: () => {
        setSavedFeedback(true)
        setTimeout(() => setSavedFeedback(false), 2000)
      },
    })
  }

  if (isLoading) {
    return <div className="h-64 bg-muted animate-pulse rounded" />
  }

  if (error) {
    return (
      <div className="rounded-md border border-destructive/50 bg-destructive/10 px-4 py-3 text-sm text-destructive">
        Failed to load: {error instanceof Error ? error.message : String(error)}
      </div>
    )
  }

  const build = (defaults.build ?? {}) as Record<string, unknown>
  const jira = (defaults.jira ?? {}) as Record<string, unknown>
  const distribution = (defaults.distribution ?? {}) as Record<string, unknown>
  const vcs = (defaults.vcs ?? {}) as Record<string, unknown>
  const escrow = (defaults.escrow ?? {}) as Record<string, unknown>

  return (
    <div className="space-y-4">
      {/* Toolbar */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <Button variant="outline" size="sm" onClick={() => { setShowRawJson(!showRawJson); if (!showRawJson) setJsonText(JSON.stringify(defaults, null, 2)) }}>
            <Code className="h-4 w-4" />
            {showRawJson ? 'Form View' : 'Raw JSON'}
          </Button>
        </div>
        <div className="flex items-center gap-2">
          <Button variant="outline" size="sm" onClick={() => migrateMutation.mutate()} disabled={migrateMutation.isPending || updateMutation.isPending} title="Import defaults from Git DSL">
            <Download className="h-4 w-4" />
            {migrateMutation.isPending ? 'Importing...' : 'Import from Git'}
          </Button>
          <Button variant="outline" size="sm" onClick={handleReset} disabled={updateMutation.isPending}>
            <RotateCcw className="h-4 w-4" />
            Reset
          </Button>
          <Button size="sm" onClick={handleSave} disabled={updateMutation.isPending}>
            <Save className="h-4 w-4" />
            {updateMutation.isPending ? 'Saving...' : savedFeedback ? 'Saved!' : 'Save'}
          </Button>
        </div>
      </div>

      {parseError && (
        <div className="rounded-md border border-destructive/50 bg-destructive/10 px-3 py-2 text-xs text-destructive font-mono">
          JSON error: {parseError}
        </div>
      )}

      {updateMutation.error && (
        <div className="rounded-md border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm text-destructive">
          Save failed: {updateMutation.error instanceof Error ? updateMutation.error.message : String(updateMutation.error)}
        </div>
      )}

      {showRawJson ? (
        <textarea
          className="w-full h-96 rounded-md border bg-background px-3 py-2 text-xs font-mono text-foreground focus:outline-none focus:ring-2 focus:ring-ring resize-y"
          value={jsonText}
          onChange={(e) => { setJsonText(e.target.value); setParseError(null) }}
          spellCheck={false}
        />
      ) : (
        <Tabs defaultValue="general">
          <TabsList className="flex-wrap h-auto gap-1">
            <TabsTrigger value="general">General</TabsTrigger>
            <TabsTrigger value="build">Build</TabsTrigger>
            <TabsTrigger value="jira">Jira</TabsTrigger>
            <TabsTrigger value="distribution">Distribution</TabsTrigger>
            <TabsTrigger value="vcs">VCS</TabsTrigger>
            <TabsTrigger value="escrow">Escrow</TabsTrigger>
          </TabsList>

          {/* General */}
          <TabsContent value="general" className="mt-4 space-y-4">
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
              <FieldInput label="Build System (default)">
                <EnumSelect fieldPath="buildSystem" value={defaults.buildSystem ?? ''} onValueChange={(v) => setField('buildSystem', v || undefined)} placeholder="Select build system" />
              </FieldInput>
              <FieldInput label="Build File Path">
                <Input value={defaults.buildFilePath ?? ''} onChange={(e) => setField('buildFilePath', e.target.value || undefined)} />
              </FieldInput>
              <FieldInput label="Artifact ID Pattern">
                <Input value={defaults.artifactIdPattern ?? ''} onChange={(e) => setField('artifactIdPattern', e.target.value || undefined)} className="font-mono text-xs" />
              </FieldInput>
              <FieldInput label="Group ID Pattern">
                <Input value={defaults.groupIdPattern ?? ''} onChange={(e) => setField('groupIdPattern', e.target.value || undefined)} className="font-mono text-xs" />
              </FieldInput>
              <FieldInput label="Display Name">
                <Input value={defaults.componentDisplayName ?? ''} onChange={(e) => setField('componentDisplayName', e.target.value || undefined)} />
              </FieldInput>
              <FieldInput label="Component Owner">
                <Input value={defaults.componentOwner ?? ''} onChange={(e) => setField('componentOwner', e.target.value || undefined)} />
              </FieldInput>
              <FieldInput label="Release Manager">
                <Input value={defaults.releaseManager ?? ''} onChange={(e) => setField('releaseManager', e.target.value || undefined)} />
              </FieldInput>
              <FieldInput label="Security Champion">
                <Input value={defaults.securityChampion ?? ''} onChange={(e) => setField('securityChampion', e.target.value || undefined)} />
              </FieldInput>
              <FieldInput label="System">
                <Input value={defaults.system ?? ''} onChange={(e) => setField('system', e.target.value || undefined)} />
              </FieldInput>
              <FieldInput label="Client Code">
                <Input value={defaults.clientCode ?? ''} onChange={(e) => setField('clientCode', e.target.value || undefined)} />
              </FieldInput>
              <FieldInput label="Copyright">
                <Input value={defaults.copyright ?? ''} onChange={(e) => setField('copyright', e.target.value || undefined)} />
              </FieldInput>
              <FieldInput label="Octopus Version">
                <Input value={defaults.octopusVersion ?? ''} onChange={(e) => setField('octopusVersion', e.target.value || undefined)} />
              </FieldInput>
            </div>
            <Separator />
            <div className="flex flex-wrap gap-6">
              <SwitchField label="Solution" checked={defaults.solution ?? false} onChange={(v) => setField('solution', v)} />
              <SwitchField label="Archived" checked={defaults.archived ?? false} onChange={(v) => setField('archived', v)} />
              <SwitchField label="Deprecated" checked={defaults.deprecated ?? false} onChange={(v) => setField('deprecated', v)} />
              <SwitchField label="Releases in Default Branch" checked={defaults.releasesInDefaultBranch ?? false} onChange={(v) => setField('releasesInDefaultBranch', v)} />
            </div>
          </TabsContent>

          {/* Build */}
          <TabsContent value="build" className="mt-4 space-y-4">
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
              <FieldInput label="Java Version">
                <Input value={getStr(build, 'javaVersion')} onChange={(e) => setNested('build', 'javaVersion', e.target.value || undefined)} />
              </FieldInput>
              <FieldInput label="Maven Version">
                <Input value={getStr(build, 'mavenVersion')} onChange={(e) => setNested('build', 'mavenVersion', e.target.value || undefined)} />
              </FieldInput>
              <FieldInput label="Gradle Version">
                <Input value={getStr(build, 'gradleVersion')} onChange={(e) => setNested('build', 'gradleVersion', e.target.value || undefined)} />
              </FieldInput>
              <FieldInput label="Project Version">
                <Input value={getStr(build, 'projectVersion')} onChange={(e) => setNested('build', 'projectVersion', e.target.value || undefined)} />
              </FieldInput>
              <FieldInput label="System Properties">
                <Input value={getStr(build, 'systemProperties')} onChange={(e) => setNested('build', 'systemProperties', e.target.value || undefined)} />
              </FieldInput>
              <FieldInput label="Build Tasks">
                <Input value={getStr(build, 'buildTasks')} onChange={(e) => setNested('build', 'buildTasks', e.target.value || undefined)} />
              </FieldInput>
            </div>
            <SwitchField label="Required Project" checked={getBool(build, 'requiredProject')} onChange={(v) => setNested('build', 'requiredProject', v)} />
          </TabsContent>

          {/* Jira */}
          <TabsContent value="jira" className="mt-4 space-y-4">
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <FieldInput label="Project Key">
                <Input value={getStr(jira, 'projectKey')} onChange={(e) => setNested('jira', 'projectKey', e.target.value || undefined)} />
              </FieldInput>
              <FieldInput label="Display Name">
                <Input value={getStr(jira, 'displayName')} onChange={(e) => setNested('jira', 'displayName', e.target.value || undefined)} />
              </FieldInput>
            </div>
            <SwitchField label="Technical" checked={getBool(jira, 'technical')} onChange={(v) => setNested('jira', 'technical', v)} />
          </TabsContent>

          {/* Distribution */}
          <TabsContent value="distribution" className="mt-4 space-y-4">
            <div className="flex flex-wrap gap-6">
              <SwitchField label="Explicit" checked={getBool(distribution, 'explicit')} onChange={(v) => setNested('distribution', 'explicit', v)} />
              <SwitchField label="External" checked={getBool(distribution, 'external')} onChange={(v) => setNested('distribution', 'external', v)} />
            </div>
            <Separator />
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <FieldInput label="GAV">
                <Input value={getStr(distribution, 'GAV')} onChange={(e) => setNested('distribution', 'GAV', e.target.value || undefined)} className="font-mono text-xs" />
              </FieldInput>
              <FieldInput label="DEB">
                <Input value={getStr(distribution, 'DEB')} onChange={(e) => setNested('distribution', 'DEB', e.target.value || undefined)} className="font-mono text-xs" />
              </FieldInput>
              <FieldInput label="RPM">
                <Input value={getStr(distribution, 'RPM')} onChange={(e) => setNested('distribution', 'RPM', e.target.value || undefined)} className="font-mono text-xs" />
              </FieldInput>
              <FieldInput label="Docker">
                <Input value={getStr(distribution, 'docker')} onChange={(e) => setNested('distribution', 'docker', e.target.value || undefined)} className="font-mono text-xs" />
              </FieldInput>
            </div>
          </TabsContent>

          {/* VCS */}
          <TabsContent value="vcs" className="mt-4 space-y-4">
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <FieldInput label="External Registry">
                <Input value={getStr(vcs, 'externalRegistry')} onChange={(e) => setNested('vcs', 'externalRegistry', e.target.value || undefined)} />
              </FieldInput>
              <FieldInput label="VCS Path">
                <Input value={getStr(vcs, 'vcsPath')} onChange={(e) => setNested('vcs', 'vcsPath', e.target.value || undefined)} className="font-mono text-xs" />
              </FieldInput>
              <FieldInput label="Repository Type">
                <EnumSelect fieldPath="repositoryType" value={getStr(vcs, 'repositoryType')} onValueChange={(v) => setNested('vcs', 'repositoryType', v || undefined)} placeholder="GIT" />
              </FieldInput>
              <FieldInput label="Tag">
                <Input value={getStr(vcs, 'tag')} onChange={(e) => setNested('vcs', 'tag', e.target.value || undefined)} className="font-mono text-xs" />
              </FieldInput>
              <FieldInput label="Branch">
                <Input value={getStr(vcs, 'branch')} onChange={(e) => setNested('vcs', 'branch', e.target.value || undefined)} className="font-mono text-xs" />
              </FieldInput>
            </div>
          </TabsContent>

          {/* Escrow */}
          <TabsContent value="escrow" className="mt-4 space-y-4">
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <FieldInput label="Build Task">
                <Input value={getStr(escrow, 'buildTask')} onChange={(e) => setNested('escrow', 'buildTask', e.target.value || undefined)} />
              </FieldInput>
              <FieldInput label="Generation">
                <EnumSelect fieldPath="generation" value={getStr(escrow, 'generation')} onValueChange={(v) => setNested('escrow', 'generation', v || undefined)} placeholder="Select generation" />
              </FieldInput>
              <FieldInput label="Disk Space">
                <Input value={getStr(escrow, 'diskSpace')} onChange={(e) => setNested('escrow', 'diskSpace', e.target.value || undefined)} />
              </FieldInput>
            </div>
            <SwitchField label="Reusable" checked={getBool(escrow, 'reusable')} onChange={(v) => setNested('escrow', 'reusable', v)} />
          </TabsContent>
        </Tabs>
      )}
    </div>
  )
}

function FieldInput({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="space-y-1.5">
      <Label className="text-sm">{label}</Label>
      {children}
    </div>
  )
}

function SwitchField({ label, checked, onChange }: { label: string; checked: boolean; onChange: (v: boolean) => void }) {
  const id = `default-${label.replace(/\s+/g, '-').toLowerCase()}`
  return (
    <div className="flex items-center gap-3">
      <Switch id={id} checked={checked} onCheckedChange={onChange} />
      <Label htmlFor={id} className="cursor-pointer">{label}</Label>
    </div>
  )
}
