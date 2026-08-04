// URL shape: /<orgId>/<page>, e.g. /fb481ced-d432-4457-8f0d-df00ce34340a/bime-products
// Mirrors AWS-console-style account-scoped URLs so a link carries its org context.

export interface Route {
  orgId: string | null
  page: string | null
}

export function parseRoute(pathname: string): Route {
  const [orgId = null, page = null] = pathname.split('/').filter(Boolean)
  return { orgId, page }
}

export function buildPath(orgId: string, page?: string): string {
  return page ? `/${orgId}/${page}` : `/${orgId}`
}
