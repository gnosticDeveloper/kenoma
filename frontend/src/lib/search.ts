function tokenize(text: string): string[] {
  return text.toLowerCase().split(/[\s-]+/).filter(Boolean)
}

// Order-independent segment match: every token in the query must appear as a substring of
// some token in the target, regardless of which order they appear in. Lets "M black" find
// "HOOD-001-BLACK-M" even though the query's segments are reversed relative to the SKU.
export function tokenizedMatch(query: string, target: string): boolean {
  const queryTokens = tokenize(query)
  if (queryTokens.length === 0) return true
  const targetTokens = tokenize(target)
  return queryTokens.every(qt => targetTokens.some(tt => tt.includes(qt)))
}
