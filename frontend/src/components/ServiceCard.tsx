import type { ServiceResponse } from '../types'
import { CopyButton } from './CopyButton'

export function ServiceCard({ svc }: { svc: ServiceResponse }) {
  return (
    <div className="entity-card">
      <div className="entity-name">{svc.name}</div>
      {svc.description && <div className="entity-detail">{svc.description}</div>}
      <div className="entity-id-row">
        <span className="entity-id-label">ID</span>
        <span className="entity-id-value">{svc.id}</span>
        <CopyButton text={svc.id} />
      </div>
    </div>
  )
}
