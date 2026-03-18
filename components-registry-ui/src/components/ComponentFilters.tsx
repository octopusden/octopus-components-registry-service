import { useEffect, useRef, useState } from 'react'
import { Search } from 'lucide-react'
import { Input } from './ui/input'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from './ui/select'
import { Button } from './ui/button'
import type { ComponentFilter } from '../lib/types'

interface ComponentFiltersProps {
  filter: ComponentFilter
  onFilterChange: (filter: ComponentFilter) => void
}

// Common system and product type values — can be extended later from API
const SYSTEM_OPTIONS = [
  'ALFA', 'BRAVO', 'CHARLIE', 'DELTA', 'ECHO',
]

const PRODUCT_TYPE_OPTIONS = [
  'PRODUCT', 'COMPONENT', 'LIBRARY', 'SERVICE',
]

const ALL_VALUE = '__all__'

export function ComponentFilters({ filter, onFilterChange }: ComponentFiltersProps) {
  const [searchValue, setSearchValue] = useState(filter.search ?? '')
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  // Sync external filter.search into local state when it changes from outside
  useEffect(() => {
    setSearchValue(filter.search ?? '')
  }, [filter.search])

  const handleSearchChange = (value: string) => {
    setSearchValue(value)
    if (debounceRef.current) clearTimeout(debounceRef.current)
    debounceRef.current = setTimeout(() => {
      onFilterChange({ ...filter, search: value || undefined })
    }, 300)
  }

  const handleSystemChange = (value: string) => {
    onFilterChange({ ...filter, system: value === ALL_VALUE ? undefined : value })
  }

  const handleProductTypeChange = (value: string) => {
    onFilterChange({ ...filter, productType: value === ALL_VALUE ? undefined : value })
  }

  const handleArchivedToggle = () => {
    if (filter.archived === undefined) {
      onFilterChange({ ...filter, archived: true })
    } else if (filter.archived === true) {
      onFilterChange({ ...filter, archived: false })
    } else {
      onFilterChange({ ...filter, archived: undefined })
    }
  }

  const handleClearAll = () => {
    setSearchValue('')
    onFilterChange({})
  }

  const hasActiveFilters =
    !!filter.search || !!filter.system || !!filter.productType || filter.archived !== undefined

  const archivedLabel =
    filter.archived === undefined
      ? 'All'
      : filter.archived
      ? 'Archived only'
      : 'Active only'

  return (
    <div className="flex flex-wrap items-center gap-3">
      <div className="relative flex-1 min-w-[200px] max-w-xs">
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
        <Input
          placeholder="Search components..."
          value={searchValue}
          onChange={(e) => handleSearchChange(e.target.value)}
          className="pl-9"
        />
      </div>

      <Select value={filter.system ?? ALL_VALUE} onValueChange={handleSystemChange}>
        <SelectTrigger className="w-[160px]">
          <SelectValue placeholder="All systems" />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value={ALL_VALUE}>All systems</SelectItem>
          {SYSTEM_OPTIONS.map((sys) => (
            <SelectItem key={sys} value={sys}>
              {sys}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>

      <Select value={filter.productType ?? ALL_VALUE} onValueChange={handleProductTypeChange}>
        <SelectTrigger className="w-[160px]">
          <SelectValue placeholder="All types" />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value={ALL_VALUE}>All types</SelectItem>
          {PRODUCT_TYPE_OPTIONS.map((pt) => (
            <SelectItem key={pt} value={pt}>
              {pt}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>

      <Button variant="outline" size="sm" onClick={handleArchivedToggle}>
        {archivedLabel}
      </Button>

      {hasActiveFilters && (
        <Button variant="ghost" size="sm" onClick={handleClearAll}>
          Clear filters
        </Button>
      )}
    </div>
  )
}
