import { useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { CheckIcon, ChevronDownIcon, CloseIcon, SearchIcon } from './icons'

export interface ComboboxItem {
  id: string
  label: string
  sublabel?: string
}

function useFilteredItems(items: ComboboxItem[], query: string): ComboboxItem[] {
  return useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return items
    return items.filter(i => i.label.toLowerCase().includes(q) || i.sublabel?.toLowerCase().includes(q))
  }, [items, query])
}

function useOutsideClose(open: boolean, onClose: () => void) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!open) return
    function onDown(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) onClose()
    }
    window.addEventListener('mousedown', onDown)
    return () => window.removeEventListener('mousedown', onDown)
  }, [open, onClose])
  return ref
}

interface Props {
  items: ComboboxItem[]
  value: string | null
  onChange: (id: string | null) => void
  placeholder: string
  loading?: boolean
  disabled?: boolean
}

export function Combobox({ items, value, onChange, placeholder, loading, disabled }: Props) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [highlight, setHighlight] = useState(0)
  const filtered = useFilteredItems(items, query)
  const ref = useOutsideClose(open, () => setOpen(false))
  const selected = items.find(i => i.id === value) ?? null

  function pick(item: ComboboxItem) {
    onChange(item.id)
    setQuery('')
    setOpen(false)
  }

  return (
    <div className="combobox" ref={ref}>
      <div className={`combobox-control${disabled ? ' disabled' : ''}`} onClick={() => !disabled && setOpen(o => !o)}>
        <SearchIcon className="combobox-search-icon" />
        {open ? (
          <input
            autoFocus
            className="combobox-input"
            value={query}
            placeholder={selected?.label ?? placeholder}
            onChange={e => { setQuery(e.target.value); setHighlight(0) }}
            onKeyDown={e => {
              if (e.key === 'ArrowDown') { e.preventDefault(); setHighlight(h => Math.min(h + 1, filtered.length - 1)) }
              if (e.key === 'ArrowUp') { e.preventDefault(); setHighlight(h => Math.max(h - 1, 0)) }
              if (e.key === 'Enter' && filtered[highlight]) { e.preventDefault(); pick(filtered[highlight]) }
              if (e.key === 'Escape') setOpen(false)
            }}
          />
        ) : (
          <span className={`combobox-value${selected ? '' : ' placeholder'}`}>
            {selected ? selected.label : placeholder}
          </span>
        )}
        {selected && !open && (
          <button
            type="button"
            className="combobox-clear"
            onClick={e => { e.stopPropagation(); onChange(null) }}
            aria-label={t('common.aria.clearSelection')}
          >
            <CloseIcon width={14} height={14} />
          </button>
        )}
        <ChevronDownIcon className="combobox-chevron" />
      </div>
      {open && (
        <div className="combobox-dropdown">
          {loading && <div className="combobox-empty">{t('common.actions.loading')}</div>}
          {!loading && filtered.length === 0 && <div className="combobox-empty">{t('common.combobox.noMatches')}</div>}
          {!loading && filtered.map((item, i) => (
            <div
              key={item.id}
              className={`combobox-option${i === highlight ? ' highlighted' : ''}${item.id === value ? ' selected' : ''}`}
              onMouseEnter={() => setHighlight(i)}
              onClick={() => pick(item)}
            >
              <div>
                <div className="combobox-option-label">{item.label}</div>
                {item.sublabel && <div className="combobox-option-sublabel">{item.sublabel}</div>}
              </div>
              {item.id === value && <CheckIcon width={14} height={14} />}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

interface MultiProps {
  items: ComboboxItem[]
  value: string[]
  onChange: (ids: string[]) => void
  placeholder: string
  disabled?: boolean
}

export function MultiCombobox({ items, value, onChange, placeholder, disabled }: MultiProps) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const filtered = useFilteredItems(items, query).filter(i => !value.includes(i.id))
  const ref = useOutsideClose(open, () => setOpen(false))
  const selectedItems = value.map(id => items.find(i => i.id === id)).filter((i): i is ComboboxItem => !!i)

  return (
    <div className="combobox multi" ref={ref}>
      <div className={`combobox-control multi${disabled ? ' disabled' : ''}`} onClick={() => !disabled && setOpen(true)}>
        <div className="combobox-chips">
          {selectedItems.map(item => (
            <span key={item.id} className="combobox-chip">
              {item.label}
              <button
                type="button"
                onClick={e => { e.stopPropagation(); onChange(value.filter(id => id !== item.id)) }}
                aria-label={t('common.aria.removeItem', { label: item.label })}
              >
                <CloseIcon width={12} height={12} />
              </button>
            </span>
          ))}
          <input
            className="combobox-input"
            value={query}
            placeholder={selectedItems.length === 0 ? placeholder : ''}
            onChange={e => { setQuery(e.target.value); setOpen(true) }}
            onFocus={() => setOpen(true)}
          />
        </div>
      </div>
      {open && (
        <div className="combobox-dropdown">
          {filtered.length === 0 && <div className="combobox-empty">{t('common.combobox.noMoreOptions')}</div>}
          {filtered.map(item => (
            <div
              key={item.id}
              className="combobox-option"
              onClick={() => { onChange([...value, item.id]); setQuery('') }}
            >
              <div>
                <div className="combobox-option-label">{item.label}</div>
                {item.sublabel && <div className="combobox-option-sublabel">{item.sublabel}</div>}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
