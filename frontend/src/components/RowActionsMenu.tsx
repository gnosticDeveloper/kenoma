import { useEffect, useRef, useState } from 'react'
import { MoreIcon } from './icons'

export interface RowAction {
  label: string
  onClick: () => void
  danger?: boolean
}

export function RowActionsMenu({ actions }: { actions: RowAction[] }) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    function onDown(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
    }
    window.addEventListener('mousedown', onDown)
    return () => window.removeEventListener('mousedown', onDown)
  }, [open])

  return (
    <div className="row-actions-menu" ref={ref} onClick={e => e.stopPropagation()}>
      <button type="button" className="row-actions-trigger" onClick={() => setOpen(o => !o)} aria-label="Row actions">
        <MoreIcon />
      </button>
      {open && (
        <div className="row-actions-dropdown">
          {actions.map(a => (
            <button
              key={a.label}
              type="button"
              className={`row-actions-item${a.danger ? ' danger' : ''}`}
              onClick={() => { setOpen(false); a.onClick() }}
            >
              {a.label}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
