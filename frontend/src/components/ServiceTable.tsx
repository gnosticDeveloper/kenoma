import type { ServiceResponse } from '../types'
import { CopyButton } from './CopyButton'

export function ServiceTable({ services }: { services: ServiceResponse[] }) {
  if (services.length === 0) {
    return <div className="empty-state">No services registered yet.</div>
  }
  return (
    <table className="data-table">
      <thead>
        <tr>
          <th>Name</th>
          <th>Description</th>
          <th>ID</th>
        </tr>
      </thead>
      <tbody>
        {services.map(s => (
          <tr key={s.id}>
            <td>{s.name}</td>
            <td className="td-muted">{s.description}</td>
            <td>
              <div className="td-id">
                <span>{s.id}</span>
                <CopyButton text={s.id} />
              </div>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
