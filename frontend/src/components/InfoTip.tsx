import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'

interface InfoTipProps {
  /** Accessible label for the trigger button, e.g. "What is a GS1-128 label?" */
  label: string
  children: ReactNode
}

const PANEL_WIDTH = 300
const MARGIN = 12
const GAP = 8

/** A small "i" trigger that reveals a floating panel of help content.
  * The panel is portaled to <body>, fixed-positioned, flips above/below the
  * trigger by available room, and scrolls internally so it never leaves the
  * viewport. Closes on outside click or Escape. */
export function InfoTip({ label, children }: InfoTipProps) {
  const [open, setOpen] = useState(false)
  const [style, setStyle] = useState<{ top: number; left: number; width: number; maxHeight: number }>({
    top: -9999, left: -9999, width: PANEL_WIDTH, maxHeight: 0,
  })
  const triggerRef = useRef<HTMLButtonElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)
  const panelId = useId()

  const reposition = useCallback(() => {
    const trigger = triggerRef.current
    const panel = panelRef.current
    if (!trigger || !panel) return
    const r = trigger.getBoundingClientRect()
    const vw = window.innerWidth
    const vh = window.innerHeight

    const width = Math.min(PANEL_WIDTH, vw - 2 * MARGIN)
    let left = r.left
    if (left + width > vw - MARGIN) left = vw - MARGIN - width
    if (left < MARGIN) left = MARGIN

    const spaceBelow = vh - r.bottom - GAP - MARGIN
    const spaceAbove = r.top - GAP - MARGIN
    const needed = panel.scrollHeight
    const below = spaceBelow >= needed || spaceBelow >= spaceAbove

    const maxHeight = Math.max(96, Math.min(needed, below ? spaceBelow : spaceAbove))
    const top = below ? r.bottom + GAP : Math.max(MARGIN, r.top - GAP - maxHeight)

    setStyle({ top, left, width, maxHeight })
  }, [])

  useLayoutEffect(() => {
    if (open) reposition()
  }, [open, reposition])

  useEffect(() => {
    if (!open) return
    function onDown(e: MouseEvent) {
      const target = e.target as Node
      if (triggerRef.current?.contains(target)) return
      if (panelRef.current?.contains(target)) return
      setOpen(false)
    }
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    window.addEventListener('resize', reposition)
    window.addEventListener('scroll', reposition, true)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
      window.removeEventListener('resize', reposition)
      window.removeEventListener('scroll', reposition, true)
    }
  }, [open, reposition])

  return (
    <span className="infotip">
      <button
        ref={triggerRef}
        type="button"
        className="infotip-trigger"
        aria-label={label}
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => setOpen(o => !o)}
      >
        <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true">
          <circle cx="8" cy="8" r="7" fill="none" stroke="currentColor" strokeWidth="1.4" />
          <circle cx="8" cy="4.6" r="1" fill="currentColor" />
          <rect x="7.2" y="6.8" width="1.6" height="5" rx="0.8" fill="currentColor" />
        </svg>
      </button>
      {open && createPortal(
        <div
          ref={panelRef}
          id={panelId}
          role="tooltip"
          className="infotip-panel"
          style={{ top: style.top, left: style.left, width: style.width, maxHeight: style.maxHeight }}
        >
          {children}
        </div>,
        document.body,
      )}
    </span>
  )
}
