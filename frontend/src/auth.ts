export interface JwtClaims {
  sub: string
  orgId: string
  roles: Record<string, string[]>
  exp: number
}

export interface Permissions {
  // Raum
  canManage: boolean
  canOnboard: boolean
  // Vassago
  canViewUsers: boolean
  canCreateUsers: boolean
  canEditUsers: boolean
  canOffboardUsers: boolean
  // Bime
  canViewBime: boolean
  canViewBimeCatalog: boolean
  canManageBime: boolean
  canApproveBimeTransfers: boolean
  canRecallBimeBatches: boolean
  canSellBime: boolean
}

export function parseJwtClaims(token: string): JwtClaims {
  const b64 = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
  const payload = JSON.parse(atob(b64)) as Record<string, unknown>
  const rawRoles = payload['roles']
  const roles: Record<string, string[]> =
    typeof rawRoles === 'string'
      ? (JSON.parse(rawRoles) as Record<string, string[]>)
      : (rawRoles as Record<string, string[]>)
  return {
    sub: payload['sub'] as string,
    orgId: payload['orgId'] as string,
    roles,
    exp: payload['exp'] as number,
  }
}

export function derivePermissions(claims: JwtClaims): Permissions {
  const allRoles = Object.values(claims.roles).flat()
  const vassagoAdmin = allRoles.includes('VASSAGO_ADMIN')
  const vassagoUser  = allRoles.includes('VASSAGO_MEMBER')
  const bimeManage = allRoles.includes('BIME_ADMIN') || allRoles.includes('BIME_STOCK_OPERATOR')
  const bimeApproveTransfers = allRoles.includes('BIME_ADMIN') || allRoles.includes('BIME_TRANSFER_APPROVER')
  const bimeSell = bimeManage || allRoles.includes('BIME_CASHIER')
  const bimeView   = bimeManage || bimeApproveTransfers || bimeSell || allRoles.includes('BIME_VIEWER')
  const bimeViewCatalog = bimeView || allRoles.includes('BIME_CATALOG_VIEWER')
  return {
    canManage:          allRoles.includes('RAUM_ADMIN'),
    canOnboard:         allRoles.includes('RAUM_ONBOARDING'),
    canViewUsers:       vassagoAdmin || vassagoUser,
    canCreateUsers:     vassagoAdmin,
    canEditUsers:       vassagoAdmin || vassagoUser,
    canOffboardUsers:   vassagoAdmin,
    canViewBime:        bimeView,
    canViewBimeCatalog: bimeViewCatalog,
    canManageBime:      bimeManage,
    canApproveBimeTransfers: bimeApproveTransfers,
    canRecallBimeBatches: allRoles.includes('BIME_ADMIN'),
    canSellBime:        bimeSell,
  }
}

export function jwtExp(token: string): number {
  try {
    return parseJwtClaims(token).exp
  } catch {
    return 0
  }
}
