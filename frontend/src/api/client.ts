import { API_BASE_URL } from './base'

export class ApiError extends Error {
  readonly status: number

  constructor(status: number, statusText: string, body: string) {
    super(`${status} ${statusText}${body ? ': ' + body : ''}`)
    this.status = status
  }
}

export async function req<T>(path: string, init: RequestInit, token?: string): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (token) headers['Authorization'] = `Bearer ${token}`
  const res = await fetch(`${API_BASE_URL}${path}`, { ...init, headers, credentials: 'include' })
  if (!res.ok) {
    const text = await res.text().catch(() => '')
    throw new ApiError(res.status, res.statusText, text)
  }
  if (res.status === 204) return undefined as T
  return res.json() as Promise<T>
}

export function payload(data: unknown): Pick<RequestInit, 'body'> {
  return { body: JSON.stringify(data) }
}

export function query(params: Record<string, string | string[] | undefined>): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (Array.isArray(value)) {
      value.forEach(v => v && search.append(key, v))
    } else if (value) {
      search.append(key, value)
    }
  }
  const qs = search.toString()
  return qs ? '?' + qs : ''
}
