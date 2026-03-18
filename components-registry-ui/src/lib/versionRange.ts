export function formatVersionRange(range: string): string {
  if (range === '(,)') return 'All versions'
  return range
}

export function isValidVersionRange(range: string): boolean {
  if (!range) return false
  // Basic validation: must start with ( or [ and end with ) or ]
  const trimmed = range.trim()
  if (trimmed.length < 3) return false
  const first = trimmed[0]
  const last = trimmed[trimmed.length - 1]
  if ((first !== '(' && first !== '[') || (last !== ')' && last !== ']')) return false
  // Must contain a comma
  if (!trimmed.includes(',')) return false
  return true
}
