import { ChevronLeft, ChevronRight } from 'lucide-react'
import { Button } from './ui/button'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from './ui/select'

interface PaginationProps {
  page: number
  totalPages: number
  totalElements: number
  size: number
  onPageChange: (page: number) => void
  onSizeChange: (size: number) => void
}

const PAGE_SIZES = [10, 20, 50]

export function Pagination({
  page,
  totalPages,
  totalElements,
  size,
  onPageChange,
  onSizeChange,
}: PaginationProps) {
  const start = totalElements === 0 ? 0 : page * size + 1
  const end = Math.min((page + 1) * size, totalElements)

  return (
    <div className="flex items-center justify-between gap-4 flex-wrap">
      <span className="text-sm text-muted-foreground">
        {totalElements === 0
          ? 'No results'
          : `Showing ${start}–${end} of ${totalElements}`}
      </span>

      <div className="flex items-center gap-3">
        <div className="flex items-center gap-2">
          <span className="text-sm text-muted-foreground">Rows per page</span>
          <Select
            value={String(size)}
            onValueChange={(v) => {
              onSizeChange(Number(v))
              onPageChange(0)
            }}
          >
            <SelectTrigger className="w-[70px] h-8">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {PAGE_SIZES.map((s) => (
                <SelectItem key={s} value={String(s)}>
                  {s}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        <div className="flex items-center gap-1">
          <Button
            variant="outline"
            size="icon"
            className="h-8 w-8"
            onClick={() => onPageChange(page - 1)}
            disabled={page === 0}
            aria-label="Previous page"
          >
            <ChevronLeft className="h-4 w-4" />
          </Button>
          <span className="text-sm min-w-[80px] text-center text-muted-foreground">
            Page {totalPages === 0 ? 0 : page + 1} of {totalPages}
          </span>
          <Button
            variant="outline"
            size="icon"
            className="h-8 w-8"
            onClick={() => onPageChange(page + 1)}
            disabled={page >= totalPages - 1}
            aria-label="Next page"
          >
            <ChevronRight className="h-4 w-4" />
          </Button>
        </div>
      </div>
    </div>
  )
}
