import type { UomConversionResponse } from '../types'

const EPSILON = 1e-6

function trimNumber(n: number): string {
  const rounded = Math.round(n * 1000) / 1000
  return String(rounded)
}

/**
 * Formats a base-unit quantity broken down into the largest configured alternate units first
 * (e.g. 253 "each" with a "case" = 24 conversion reads as "10 case, 13 each" instead of "253 each").
 */
export function formatQuantity(quantity: number, baseUom: string, conversions: UomConversionResponse[]): string {
  const negative = quantity < 0
  let remaining = Math.abs(quantity)

  if (conversions.length === 0) {
    return `${negative ? '-' : ''}${trimNumber(remaining)} ${baseUom}`
  }

  const parts: string[] = []
  const sorted = [...conversions].sort((a, b) => b.factor - a.factor)
  for (const c of sorted) {
    const count = Math.floor(remaining / c.factor + EPSILON)
    if (count > 0) {
      parts.push(`${count} ${c.uomName}`)
      remaining -= count * c.factor
    }
  }
  remaining = Math.round(remaining * 1000) / 1000
  if (remaining > EPSILON || parts.length === 0) {
    parts.push(`${trimNumber(remaining)} ${baseUom}`)
  }
  return (negative ? '-' : '') + parts.join(', ')
}
