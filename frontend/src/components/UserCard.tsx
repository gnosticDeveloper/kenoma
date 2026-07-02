import type { UserResponse } from '../types'
import { CopyButton } from './CopyButton'

export function UserCard({ user }: { user: UserResponse }) {
  const roleEntries = Object.entries(user.roles ?? {})

  return (
    <div className="entity-card">
      <div className="entity-name">{user.name} {user.lastName}</div>
      <div className="entity-detail">{user.email} · @{user.username}</div>
      {roleEntries.length > 0 && (
        <div className="user-roles">
          {roleEntries.map(([svcId, roles]) => (
            <div key={svcId} className="role-entry">
              <span className="role-svc-id" title={svcId}>{svcId.slice(0, 8)}…</span>
              <div className="role-chips">
                {roles.map(r => <span key={r} className="role-badge">{r}</span>)}
              </div>
            </div>
          ))}
        </div>
      )}
      <div className="entity-id-row" style={{ marginTop: roleEntries.length > 0 ? '10px' : undefined }}>
        <span className="entity-id-label">ID</span>
        <span className="entity-id-value">{user.id}</span>
        <CopyButton text={user.id} />
      </div>
    </div>
  )
}
