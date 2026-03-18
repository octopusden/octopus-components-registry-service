import { useState } from 'react'
import { Plus, Trash2, Pencil } from 'lucide-react'
import { Button } from '../ui/button'
import { Input } from '../ui/input'
import { Label } from '../ui/label'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
  DialogClose,
} from '../ui/dialog'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '../ui/table'
import {
  useFieldOverrides,
  useCreateFieldOverride,
  useUpdateFieldOverride,
  useDeleteFieldOverride,
} from '../../hooks/useComponent'
import { useToast } from '../../hooks/use-toast'
import type { FieldOverride } from '../../lib/types'

interface FieldOverridesProps {
  componentId: string
}

interface OverrideFormState {
  fieldPath: string
  versionRange: string
  value: string
}

const emptyForm: OverrideFormState = {
  fieldPath: '',
  versionRange: '',
  value: '',
}

export function FieldOverrides({ componentId }: FieldOverridesProps) {
  const { data: overrides, isLoading } = useFieldOverrides(componentId)
  const createMutation = useCreateFieldOverride(componentId)
  const updateMutation = useUpdateFieldOverride(componentId)
  const deleteMutation = useDeleteFieldOverride(componentId)
  const { toast } = useToast()

  const [dialogOpen, setDialogOpen] = useState(false)
  const [editingOverride, setEditingOverride] = useState<FieldOverride | null>(null)
  const [form, setForm] = useState<OverrideFormState>(emptyForm)
  const [deleteConfirm, setDeleteConfirm] = useState<string | null>(null)

  function openCreate() {
    setEditingOverride(null)
    setForm(emptyForm)
    setDialogOpen(true)
  }

  function openEdit(override: FieldOverride) {
    setEditingOverride(override)
    setForm({
      fieldPath: override.fieldPath,
      versionRange: override.versionRange,
      value: typeof override.value === 'string' ? override.value : JSON.stringify(override.value),
    })
    setDialogOpen(true)
  }

  function parseValue(raw: string): unknown {
    try {
      return JSON.parse(raw)
    } catch {
      return raw
    }
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    const payload = {
      fieldPath: form.fieldPath,
      versionRange: form.versionRange,
      value: parseValue(form.value),
    }
    try {
      if (editingOverride) {
        await updateMutation.mutateAsync({ overrideId: editingOverride.id, ...payload })
        toast({ title: 'Override updated' })
      } else {
        await createMutation.mutateAsync(payload)
        toast({ title: 'Override created' })
      }
      setDialogOpen(false)
    } catch (err) {
      toast({
        title: 'Error',
        description: err instanceof Error ? err.message : String(err),
        variant: 'destructive',
      })
    }
  }

  async function handleDelete(overrideId: string) {
    try {
      await deleteMutation.mutateAsync(overrideId)
      toast({ title: 'Override deleted' })
      setDeleteConfirm(null)
    } catch (err) {
      toast({
        title: 'Error',
        description: err instanceof Error ? err.message : String(err),
        variant: 'destructive',
      })
    }
  }

  if (isLoading) {
    return (
      <div className="space-y-2">
        {Array.from({ length: 3 }).map((_, i) => (
          <div key={i} className="h-10 bg-muted rounded animate-pulse" />
        ))}
      </div>
    )
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold">Field Overrides</h3>
        <Button size="sm" onClick={openCreate}>
          <Plus className="h-4 w-4" />
          Add Override
        </Button>
      </div>

      {!overrides || overrides.length === 0 ? (
        <div className="rounded-md border border-dashed p-8 text-center text-muted-foreground">
          No field overrides defined.
        </div>
      ) : (
        <div className="rounded-md border overflow-hidden">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Field Path</TableHead>
                <TableHead>Version Range</TableHead>
                <TableHead>Value</TableHead>
                <TableHead className="w-24">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {overrides.map((override) => (
                <TableRow key={override.id}>
                  <TableCell className="font-mono text-xs">{override.fieldPath}</TableCell>
                  <TableCell className="font-mono text-xs">{override.versionRange}</TableCell>
                  <TableCell className="font-mono text-xs max-w-[200px] truncate">
                    {typeof override.value === 'string'
                      ? override.value
                      : JSON.stringify(override.value)}
                  </TableCell>
                  <TableCell>
                    <div className="flex items-center gap-1">
                      <Button
                        variant="ghost"
                        size="icon"
                        className="h-8 w-8"
                        onClick={() => openEdit(override)}
                      >
                        <Pencil className="h-3.5 w-3.5" />
                      </Button>
                      <Button
                        variant="ghost"
                        size="icon"
                        className="h-8 w-8 text-destructive hover:text-destructive"
                        onClick={() => setDeleteConfirm(override.id)}
                      >
                        <Trash2 className="h-3.5 w-3.5" />
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}

      {/* Create / Edit dialog */}
      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{editingOverride ? 'Edit Override' : 'Add Override'}</DialogTitle>
          </DialogHeader>
          <form onSubmit={handleSubmit} className="space-y-4">
            <div className="space-y-1.5">
              <Label htmlFor="fieldPath">Field Path</Label>
              <Input
                id="fieldPath"
                placeholder="e.g. buildConfigurations[0].javaVersion"
                value={form.fieldPath}
                onChange={(e) => setForm((f) => ({ ...f, fieldPath: e.target.value }))}
                disabled={!!editingOverride}
                required
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="versionRange">Version Range</Label>
              <Input
                id="versionRange"
                placeholder="e.g. [1.0,2.0)"
                value={form.versionRange}
                onChange={(e) => setForm((f) => ({ ...f, versionRange: e.target.value }))}
                required
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="value">Value (JSON or plain string)</Label>
              <Input
                id="value"
                placeholder='e.g. "11" or true or {"key":"val"}'
                value={form.value}
                onChange={(e) => setForm((f) => ({ ...f, value: e.target.value }))}
                required
              />
            </div>
            <DialogFooter>
              <DialogClose asChild>
                <Button type="button" variant="outline">Cancel</Button>
              </DialogClose>
              <Button
                type="submit"
                disabled={createMutation.isPending || updateMutation.isPending}
              >
                {editingOverride ? 'Update' : 'Create'}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/* Delete confirm dialog */}
      <Dialog
        open={!!deleteConfirm}
        onOpenChange={(open) => { if (!open) setDeleteConfirm(null) }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Delete Override</DialogTitle>
          </DialogHeader>
          <p className="text-sm text-muted-foreground">
            Are you sure you want to delete this field override? This action cannot be undone.
          </p>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDeleteConfirm(null)}>
              Cancel
            </Button>
            <Button
              variant="destructive"
              onClick={() => deleteConfirm && handleDelete(deleteConfirm)}
              disabled={deleteMutation.isPending}
            >
              Delete
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
