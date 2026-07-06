import { ApiError } from './client'

const KNOWN_STATUSES = [400, 401, 403, 404, 409, 500]

export function errorKey(err: unknown): string {
  if (err instanceof ApiError && KNOWN_STATUSES.includes(err.status)) {
    return `common.errors.${err.status}`
  }
  return 'common.errors.generic'
}

export function keyForMessage(message: string): string {
  const match = /^(\d{3})\s/.exec(message)
  const status = match ? Number(match[1]) : NaN
  return KNOWN_STATUSES.includes(status) ? `common.errors.${status}` : 'common.errors.generic'
}
