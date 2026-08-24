export function formatMoney(amount: number, currency: string, locale: string): string {
  if (!currency) return String(amount)
  try {
    return new Intl.NumberFormat(locale, { style: 'currency', currency }).format(amount)
  } catch {
    return `${currency} ${amount}`
  }
}
