import { useState } from 'react'
import { Plus, X, Pencil, Check } from 'lucide-react'
import { Button } from '../ui/button'
import { Input } from '../ui/input'
import { Badge } from '../ui/badge'
import { useFieldOverrides, useCreateFieldOverride, useUpdateFieldOverride, useDeleteFieldOverride } from '../../hooks/useComponent'
import { formatVersionRange, isValidVersionRange } from '../../lib/versionRange'
import type { FieldOverride } from '../../lib/types'

interface FieldOverrideInlineProps {
  componentId: string
  fieldPath: string
}

export function FieldOverrideInline({ componentId, fieldPath }: FieldOverrideInlineProps) {
  const { data: allOverrides = [] } = useFieldOverrides(componentId)
  const createMutation = useCreateFieldOverride(componentId)
  const updateMutation = useUpdateFieldOverride(componentId)
  const deleteMutation = useDeleteFieldOverride(componentId)

  const overrides = allOverrides.filter((o) => o.fieldPath === fieldPath)

  const [adding, setAdding] = useState(false)
  const [newRange, setNewRange] = useState('(,)')
  const [newValue, setNewValue] = useState('')
  const [editingId, setEditingId] = useState<string | null>(null)
  const [editRange, setEditRange] = useState('')
  const [editValue, setEditValue] = useState('')

  function handleAdd() {
    if (!isValidVersionRange(newRange) || !newValue.trim()) return
    createMutation.mutate(
      { fieldPath, versionRange: newRange, value: newValue },
      {
        onSuccess: () => {
          setAdding(false)
          setNewRange('(,)')
          setNewValue('')
        },
      },
    )
  }

  function startEdit(override: FieldOverride) {
    setEditingId(override.id)
    setEditRange(override.versionRange)
    setEditValue(String(override.value ?? ''))
  }

  function handleUpdate() {
    if (!editingId || !isValidVersionRange(editRange)) return
    updateMutation.mutate(
      { overrideId: editingId, versionRange: editRange, value: editValue },
      { onSuccess: () => setEditingId(null) },
    )
  }

  function handleDelete(id: string) {
    deleteMutation.mutate(id)
  }

  if (overrides.length === 0 && !adding) {
    return (
      <button
        type="button"
        onClick={() => setAdding(true)}
        className="text-xs text-muted-foreground hover:text-foreground flex items-center gap-1 mt-1"
      >
        <Plus className="h-3 w-3" />
        Add override
      </button>
    )
  }

  return (
    <div className="mt-1 space-y-1">
      {overrides.map((override) => (
        <div key={override.id}>
          {editingId === override.id ? (
            <div className="flex items-center gap-1.5">
              <Input
                value={editRange}
                onChange={(e) => setEditRange(e.target.value)}
                className="h-6 w-24 text-xs font-mono px-1"
                placeholder="(,)"
              />
              <Input
                value={editValue}
                onChange={(e) => setEditValue(e.target.value)}
                className="h-6 flex-1 text-xs px-1"
              />
              <Button variant="ghost" size="sm" className="h-6 w-6 p-0" onClick={handleUpdate} disabled={updateMutation.isPending}>
                <Check className="h-3 w-3" />
              </Button>
              <Button variant="ghost" size="sm" className="h-6 w-6 p-0" onClick={() => setEditingId(null)}>
                <X className="h-3 w-3" />
              </Button>
            </div>
          ) : (
            <div className="flex items-center gap-1.5 group">
              <Badge variant="outline" className="text-xs font-mono h-5 px-1.5">
                {formatVersionRange(override.versionRange)}
              </Badge>
              <span className="text-xs text-muted-foreground">&rarr;</span>
              <span className="text-xs">{String(override.value)}</span>
              <button
                type="button"
                onClick={() => startEdit(override)}
                className="hidden group-hover:inline-flex h-4 w-4 items-center justify-center text-muted-foreground hover:text-foreground"
              >
                <Pencil className="h-3 w-3" />
              </button>
              <button
                type="button"
                onClick={() => handleDelete(override.id)}
                className="hidden group-hover:inline-flex h-4 w-4 items-center justify-center text-destructive hover:text-destructive"
              >
                <X className="h-3 w-3" />
              </button>
            </div>
          )}
        </div>
      ))}

      {adding ? (
        <div className="flex items-center gap-1.5">
          <Input
            value={newRange}
            onChange={(e) => setNewRange(e.target.value)}
            className="h-6 w-24 text-xs font-mono px-1"
            placeholder="(,)"
            autoFocus
          />
          <Input
            value={newValue}
            onChange={(e) => setNewValue(e.target.value)}
            className="h-6 flex-1 text-xs px-1"
            placeholder="Override value"
          />
          <Button variant="ghost" size="sm" className="h-6 w-6 p-0" onClick={handleAdd} disabled={createMutation.isPending}>
            <Check className="h-3 w-3" />
          </Button>
          <Button variant="ghost" size="sm" className="h-6 w-6 p-0" onClick={() => setAdding(false)}>
            <X className="h-3 w-3" />
          </Button>
        </div>
      ) : (
        <button
          type="button"
          onClick={() => setAdding(true)}
          className="text-xs text-muted-foreground hover:text-foreground flex items-center gap-1"
        >
          <Plus className="h-3 w-3" />
          Add override
        </button>
      )}
    </div>
  )
}
