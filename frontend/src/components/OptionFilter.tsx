import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import type { ProductMetadataResponse } from '../types'

export function FilterChips({ metadataDefs, selectedOptionIds, onToggle, onClear, matchAll, onMatchAllChange }: {
  metadataDefs: ProductMetadataResponse[]
  selectedOptionIds: string[]
  onToggle: (optionId: string) => void
  onClear: () => void
  matchAll: boolean
  onMatchAllChange: (matchAll: boolean) => void
}) {
  const { t } = useTranslation()
  const groups = metadataDefs.filter(m => m.options.length > 0)
  if (groups.length === 0) return null
  return (
    <div className="filter-chips-panel">
      {groups.map(m => (
        <div key={m.id} className="filter-chip-group">
          <span className="filter-chip-group-label">{m.name}</span>
          <div className="filter-chip-row">
            {m.options.map(o => {
              const active = selectedOptionIds.includes(o.id)
              return (
                <button
                  key={o.id}
                  type="button"
                  className={`filter-chip${active ? ' filter-chip-active' : ''}`}
                  onClick={() => onToggle(o.id)}
                >
                  {o.value}
                </button>
              )
            })}
          </div>
        </div>
      ))}
      <div className="filter-chip-footer">
        {selectedOptionIds.length > 1 && (
          <div className="filter-match-toggle">
            <button
              type="button"
              className={`filter-match-option${!matchAll ? ' filter-match-option-active' : ''}`}
              onClick={() => onMatchAllChange(false)}
            >
              {t('common.filters.matchAny')}
            </button>
            <button
              type="button"
              className={`filter-match-option${matchAll ? ' filter-match-option-active' : ''}`}
              onClick={() => onMatchAllChange(true)}
            >
              {t('common.filters.matchAll')}
            </button>
          </div>
        )}
        {selectedOptionIds.length > 0 && (
          <button type="button" className="filter-chip-clear" onClick={onClear}>
            {t('common.filters.clear')}
          </button>
        )}
      </div>
    </div>
  )
}

export function FilterDisclosure({ activeCount, children }: { activeCount: number; children: ReactNode }) {
  const { t } = useTranslation()
  return (
    <details className="filter-disclosure" open>
      <summary>
        {t('common.filters.byOptions')}
        {activeCount > 0 && <span className="filter-count-badge">{activeCount}</span>}
      </summary>
      <div className="filter-disclosure-body">{children}</div>
    </details>
  )
}

export function toggleOptionId(prev: string[], optionId: string): string[] {
  return prev.includes(optionId) ? prev.filter(id => id !== optionId) : [...prev, optionId]
}

export function matchesOptionFilter(rowOptionIds: string[], selectedOptionIds: string[], matchAll: boolean): boolean {
  if (selectedOptionIds.length === 0) return true
  return matchAll
    ? selectedOptionIds.every(id => rowOptionIds.includes(id))
    : selectedOptionIds.some(id => rowOptionIds.includes(id))
}
