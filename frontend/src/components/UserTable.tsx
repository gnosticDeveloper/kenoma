import type { UserResponse } from '../types'
import { CopyButton } from './CopyButton'

export function UserTable({ users }: { users: UserResponse[] }) {
  if (users.length === 0) {
    return <div className="empty-state">No users found.</div>
  }

  return (
    <table className="data-table">
      <thead>
        <tr>
          <th>Name</th>
          <th>Username</th>
          <th>Email</th>
          <th>Roles</th>
          <th>ID</th>
        </tr>
      </thead>
      <tbody>
        {users.map(u => (
          <tr key={u.id}>
            <td>{u.name} {u.lastName}</td>
            <td className="td-muted">@{u.username}</td>
            <td className="td-muted">{u.email}</td>
            <td>
              <div className="role-chips">
                {Object.values(u.roles ?? {}).flat().map((r, i) => (
                  <span key={i} className="role-badge">{r}</span>
                ))}
              </div>
            </td>
            <td>
              <div className="td-id">
                <span>{u.id}</span>
                <CopyButton text={u.id} />
              </div>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
