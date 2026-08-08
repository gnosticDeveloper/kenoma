import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { useTranslation } from 'react-i18next'
import { MoreIcon } from './icons'

export interface RowAction {
  label: string
  onClick: () => void
  danger?: boolean
}

export function RowActionsMenu({ actions }: { actions: RowAction[] }) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const [position, setPosition] = useState({ top: 0, left: 0 })
  const triggerRef = useRef<HTMLButtonElement>(null)
  const dropdownRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    function onDown(e: MouseEvent) {
      if (
        dropdownRef.current && !dropdownRef.current.contains(e.target as Node) &&
        triggerRef.current && !triggerRef.current.contains(e.target as Node)
      ) {
        setOpen(false)
      }
    }
    window.addEventListener('mousedown', onDown)
    return () => window.removeEventListener('mousedown', onDown)
  }, [open])

  function toggle() {
    if (!open && triggerRef.current) {
      const rect = triggerRef.current.getBoundingClientRect()
      setPosition({ top: rect.bottom + 4, left: rect.right })
    }
    setOpen(o => !o)
  }

  return (
    <div className="row-actions-menu" onClick={e => e.stopPropagation()}>
      <button type="button" ref={triggerRef} className="row-actions-trigger" onClick={toggle} aria-label={t('common.aria.rowActions')}>
        <MoreIcon />
      </button>
      {open && createPortal(
        <div
          className="row-actions-dropdown row-actions-dropdown-portal"
          ref={dropdownRef}
          style={{ top: position.top, left: position.left }}
        >
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
        </div>,
        document.body
      )}
    </div>
  )
}
