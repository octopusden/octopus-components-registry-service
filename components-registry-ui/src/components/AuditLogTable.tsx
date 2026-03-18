import { useState } from 'react'
import { ChevronDown, ChevronRight } from 'lucide-react'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from './ui/table'
import { Badge } from './ui/badge'
import { Button } from './ui/button'
import { AuditDiffViewer } from './AuditDiffViewer'
import type { AuditLogEntry } from '../lib/types'
import { cn } from '../lib/utils'

interface AuditLogTableProps {
  data: AuditLogEntry[]
  isLoading: boolean
}

const ACTION_BADGE_CLASSES: Record<string, string> = {
  CREATE: 'border-transparent bg-green-100 text-green-800 dark:bg-green-900/40 dark:text-green-300',
  UPDATE: 'border-transparent bg-blue-100 text-blue-800 dark:bg-blue-900/40 dark:text-blue-300',
  DELETE: 'border-transparent bg-red-100 text-red-800 dark:bg-red-900/40 dark:text-red-300',
}

function formatDate(dateStr: string): string {
  try {
    return new Intl.DateTimeFormat(undefined, {
      year: 'numeric',
      month: 'short',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    }).format(new Date(dateStr))
  } catch {
    return dateStr
  }
}

function diffSummary(entry: AuditLogEntry): string | null {
  if (!entry.changeDiff) return null
  const keys = Object.keys(entry.changeDiff)
  if (keys.length === 0) return null
  if (keys.length <= 3) return keys.join(', ')
  return `${keys.slice(0, 3).join(', ')} +${keys.length - 3} more`
}

function SkeletonRow() {
  return (
    <TableRow>
      {Array.from({ length: 6 }).map((_, i) => (
        <TableCell key={i}>
          <div className="h-4 bg-muted animate-pulse rounded w-3/4" />
        </TableCell>
      ))}
    </TableRow>
  )
}

export function AuditLogTable({ data, isLoading }: AuditLogTableProps) {
  const [expandedId, setExpandedId] = useState<number | null>(null)

  const toggleExpand = (id: number) => {
    setExpandedId((prev) => (prev === id ? null : id))
  }

  return (
    <div className="rounded-md border">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead className="w-8" />
            <TableHead>Who</TableHead>
            <TableHead>When</TableHead>
            <TableHead>Entity Type</TableHead>
            <TableHead>Entity ID</TableHead>
            <TableHead>Action</TableHead>
            <TableHead>Changed Fields</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {isLoading
            ? Array.from({ length: 5 }).map((_, i) => <SkeletonRow key={i} />)
            : data.length === 0
            ? (
              <TableRow>
                <TableCell colSpan={7} className="text-center text-muted-foreground py-8">
                  No audit log entries found.
                </TableCell>
              </TableRow>
            )
            : data.map((entry) => {
                const isExpanded = expandedId === entry.id
                const summary = diffSummary(entry)
                const actionClass = ACTION_BADGE_CLASSES[entry.action] ?? 'border-transparent bg-muted text-muted-foreground'

                return (
                  <>
                    <TableRow
                      key={entry.id}
                      className={cn('cursor-pointer', isExpanded && 'bg-muted/40')}
                      onClick={() => toggleExpand(entry.id)}
                    >
                      <TableCell className="pr-0">
                        <Button variant="ghost" size="icon" className="h-6 w-6" tabIndex={-1}>
                          {isExpanded
                            ? <ChevronDown className="h-4 w-4" />
                            : <ChevronRight className="h-4 w-4" />
                          }
                        </Button>
                      </TableCell>
                      <TableCell className="font-medium">
                        {entry.changedBy ?? <span className="text-muted-foreground italic">system</span>}
                      </TableCell>
                      <TableCell className="text-muted-foreground whitespace-nowrap">
                        {formatDate(entry.changedAt)}
                      </TableCell>
                      <TableCell>
                        <span className="font-mono text-xs bg-muted px-1.5 py-0.5 rounded">
                          {entry.entityType}
                        </span>
                      </TableCell>
                      <TableCell>
                        <span className="font-mono text-xs">{entry.entityId}</span>
                      </TableCell>
                      <TableCell>
                        <Badge className={actionClass}>
                          {entry.action}
                        </Badge>
                      </TableCell>
                      <TableCell className="text-xs text-muted-foreground">
                        {summary ?? '—'}
                      </TableCell>
                    </TableRow>
                    {isExpanded && (
                      <TableRow key={`${entry.id}-diff`}>
                        <TableCell colSpan={7} className="bg-muted/20 p-4">
                          {entry.correlationId && (
                            <div className="text-xs text-muted-foreground mb-3">
                              Correlation ID:{' '}
                              <span className="font-mono">{entry.correlationId}</span>
                            </div>
                          )}
                          <AuditDiffViewer entry={entry} />
                        </TableCell>
                      </TableRow>
                    )}
                  </>
                )
              })}
        </TableBody>
      </Table>
    </div>
  )
}
