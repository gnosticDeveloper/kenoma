import type { ReactNode } from 'react'

interface Tab {
  id: string
  label: string
}

interface Props {
  tabs: Tab[]
  active: string
  onChange: (id: string) => void
  children: ReactNode
}

export function Tabs({ tabs, active, onChange, children }: Props) {
  return (
    <div className="tabs-wrap">
      <div className="tabs-strip" role="tablist">
        {tabs.map(t => (
          <button
            key={t.id}
            role="tab"
            aria-selected={t.id === active}
            className={`tab-item${t.id === active ? ' active' : ''}`}
            onClick={() => onChange(t.id)}
            type="button"
          >
            {t.label}
          </button>
        ))}
      </div>
      <div className="tabs-content">{children}</div>
    </div>
  )
}
