import { useState, useRef, useEffect } from 'react'
import { Input } from './input'
import { useOwners } from '../../hooks/useOwners'

interface PeopleInputProps {
  value: string
  onChange: (value: string) => void
  placeholder?: string
  lookupFn?: (query: string) => Promise<{ id: string; displayName: string; email: string }[]>
}

export function PeopleInput({ value, onChange, placeholder = 'owner@example.com', lookupFn }: PeopleInputProps) {
  const { data: owners = [] } = useOwners()
  const [open, setOpen] = useState(false)
  const [inputValue, setInputValue] = useState(value)
  const [externalResults, setExternalResults] = useState<{ id: string; displayName: string; email: string }[]>([])
  const wrapperRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    setInputValue(value)
  }, [value])

  useEffect(() => {
    function handleClickOutside(e: MouseEvent) {
      if (wrapperRef.current && !wrapperRef.current.contains(e.target as Node)) {
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [])

  useEffect(() => {
    if (!lookupFn || !inputValue || inputValue.length < 2) {
      setExternalResults([])
      return
    }
    let cancelled = false
    const timer = setTimeout(async () => {
      try {
        const results = await lookupFn(inputValue)
        if (!cancelled) setExternalResults(results)
      } catch {
        if (!cancelled) setExternalResults([])
      }
    }, 300)
    return () => { cancelled = true; clearTimeout(timer) }
  }, [inputValue, lookupFn])

  const filtered = inputValue
    ? owners.filter((o) => o.toLowerCase().includes(inputValue.toLowerCase()))
    : owners

  const suggestions = [
    ...filtered.map((o) => ({ label: o, value: o })),
    ...externalResults
      .filter((r) => !filtered.includes(r.email))
      .map((r) => ({ label: `${r.displayName} (${r.email})`, value: r.email })),
  ].slice(0, 10)

  return (
    <div ref={wrapperRef} className="relative">
      <Input
        value={inputValue}
        onChange={(e) => {
          setInputValue(e.target.value)
          setOpen(true)
        }}
        onFocus={() => setOpen(true)}
        onBlur={() => {
          onChange(inputValue)
        }}
        placeholder={placeholder}
      />
      {open && suggestions.length > 0 && (
        <div className="absolute z-50 mt-1 w-full rounded-md border bg-popover shadow-md max-h-48 overflow-auto">
          {suggestions.map((s) => (
            <button
              key={s.value}
              type="button"
              className="w-full px-3 py-1.5 text-left text-sm hover:bg-accent hover:text-accent-foreground"
              onMouseDown={(e) => {
                e.preventDefault()
                setInputValue(s.value)
                onChange(s.value)
                setOpen(false)
              }}
            >
              {s.label}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
