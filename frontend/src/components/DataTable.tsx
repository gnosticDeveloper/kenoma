import { useMemo, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { SearchIcon } from './icons'

export interface Column<T> {
  key: string
  header: string
  render: (row: T) => ReactNode
  sortValue?: (row: T) => string | number
}

interface Props<T> {
  columns: Column<T>[]
  rows: T[]
  rowKey: (row: T) => string
  searchable?: boolean
  searchText?: (row: T) => string
  onRowClick?: (row: T) => void
  emptyLabel: string
  headerAction?: ReactNode
}

export function DataTable<T>({ columns, rows, rowKey, searchable, searchText, onRowClick, emptyLabel, headerAction }: Props<T>) {
  const { t } = useTranslation()
  const [search, setSearch] = useState('')
  const [sort, setSort] = useState<{ key: string; dir: 1 | -1 } | null>(null)

  const filtered = useMemo(() => {
    if (!search.trim() || !searchText) return rows
    const q = search.trim().toLowerCase()
    return rows.filter(r => searchText(r).toLowerCase().includes(q))
  }, [rows, search, searchText])

  const sorted = useMemo(() => {
    if (!sort) return filtered
    const col = columns.find(c => c.key === sort.key)
    if (!col?.sortValue) return filtered
    return [...filtered].sort((a, b) => {
      const av = col.sortValue!(a)
      const bv = col.sortValue!(b)
      if (av < bv) return -sort.dir
      if (av > bv) return sort.dir
      return 0
    })
  }, [filtered, sort, columns])

  function toggleSort(key: string) {
    setSort(s => (s?.key === key ? { key, dir: s.dir === 1 ? -1 : 1 } : { key, dir: 1 }))
  }

  return (
    <div className="data-table-wrap">
      {(searchable || headerAction) && (
        <div className="data-table-toolbar">
          {searchable && (
            <div className="data-table-search">
              <SearchIcon />
              <input value={search} onChange={e => setSearch(e.target.value)} placeholder={t('common.search')} />
            </div>
          )}
          {headerAction}
        </div>
      )}
      {sorted.length === 0 ? (
        <div className="empty-state">{emptyLabel}</div>
      ) : (
        <div className="data-table-scroll">
          <table className="data-table">
            <thead>
              <tr>
                {columns.map(c => (
                  <th
                    key={c.key}
                    className={c.sortValue ? 'sortable' : undefined}
                    onClick={() => c.sortValue && toggleSort(c.key)}
                  >
                    {c.header}
                    {sort?.key === c.key && <span className="sort-arrow">{sort.dir === 1 ? ' ▲' : ' ▼'}</span>}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {sorted.map(row => (
                <tr key={rowKey(row)} onClick={onRowClick ? () => onRowClick(row) : undefined} className={onRowClick ? 'clickable' : undefined}>
                  {columns.map(c => <td key={c.key}>{c.render(row)}</td>)}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
