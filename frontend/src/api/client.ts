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

export function query(params: Record<string, string | undefined>): string {
  const entries = Object.entries(params).filter(([, v]) => v)
  if (entries.length === 0) return ''
  return '?' + new URLSearchParams(entries as [string, string][]).toString()
}
