import type { AuditLogEntry } from '../lib/types'
import { cn } from '../lib/utils'

interface AuditDiffViewerProps {
  entry: AuditLogEntry
}

function formatValue(value: unknown): string {
  if (value === null || value === undefined) return '—'
  if (typeof value === 'object') return JSON.stringify(value, null, 2)
  return String(value)
}

function DiffPanel({
  label,
  value,
  changedKeys,
  colorClass,
  emptyLabel,
}: {
  label: string
  value: Record<string, unknown> | null
  changedKeys: Set<string>
  colorClass: string
  emptyLabel: string
}) {
  if (!value) {
    return (
      <div className="flex-1 min-w-0">
        <div className="text-xs font-semibold uppercase tracking-wide text-muted-foreground mb-2">
          {label}
        </div>
        <div className="rounded-md border bg-muted/30 p-3 text-sm text-muted-foreground italic">
          {emptyLabel}
        </div>
      </div>
    )
  }

  return (
    <div className="flex-1 min-w-0">
      <div className="text-xs font-semibold uppercase tracking-wide text-muted-foreground mb-2">
        {label}
      </div>
      <div className="rounded-md border bg-muted/20 overflow-auto max-h-80">
        <table className="w-full text-sm">
          <tbody>
            {Object.entries(value).map(([key, val]) => {
              const isChanged = changedKeys.has(key)
              return (
                <tr
                  key={key}
                  className={cn(
                    'border-b last:border-b-0',
                    isChanged && colorClass
                  )}
                >
                  <td className="px-3 py-1.5 font-mono text-xs font-medium text-muted-foreground w-2/5 align-top break-all">
                    {key}
                  </td>
                  <td className="px-3 py-1.5 font-mono text-xs break-all align-top">
                    {formatValue(val)}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </div>
  )
}

export function AuditDiffViewer({ entry }: AuditDiffViewerProps) {
  const changedKeys = new Set<string>(
    entry.changeDiff ? Object.keys(entry.changeDiff) : []
  )

  const hasData = entry.oldValue || entry.newValue

  if (!hasData) {
    return (
      <div className="px-4 py-3 text-sm text-muted-foreground italic">
        No value data recorded for this entry.
      </div>
    )
  }

  return (
    <div className="flex gap-4 flex-col sm:flex-row">
      <DiffPanel
        label="Old Value"
        value={entry.oldValue}
        changedKeys={changedKeys}
        colorClass="bg-red-50 dark:bg-red-950/30"
        emptyLabel="No previous value (record created)"
      />
      <DiffPanel
        label="New Value"
        value={entry.newValue}
        changedKeys={changedKeys}
        colorClass="bg-green-50 dark:bg-green-950/30"
        emptyLabel="No new value (record deleted)"
      />
    </div>
  )
}
