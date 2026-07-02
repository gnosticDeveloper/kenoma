import type { OrgResponse } from '../types'
import { CopyButton } from './CopyButton'

export function OrgCard({ org }: { org: OrgResponse }) {
  return (
    <div className="entity-card">
      <div className="entity-name">{org.name}</div>
      <div className="entity-detail">{org.contactEmail}</div>
      <div className="entity-id-row">
        <span className="entity-id-label">ID</span>
        <span className="entity-id-value">{org.id}</span>
        <CopyButton text={org.id} />
      </div>
    </div>
  )
}
