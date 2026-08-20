import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { raum } from '../api/raum'
import { useApiCall } from '../hooks/useApiCall'
import { useToast } from '../components/Toast'
import { Feedback } from '../components/Feedback'
import type { DrBackupResponse, OrgResponse } from '../types'

interface Props { token: string }

interface BackupGroup {
  key: string
  label: string
  dateLabel: string
  backups: DrBackupResponse[]
  latestTs: number
}

function rowLabel(b: DrBackupResponse): string {
  return b.scope === 'ORG' ? (b.serviceName ?? '—') : `${b.instanceHost}:${b.instancePort}/${b.instanceDb}`
}

export default function DrBackupsPage({ token }: Props) {
  const { t } = useTranslation()
  const toast = useToast()

  const orgs = useApiCall<OrgResponse[]>()
  const backups = useApiCall<DrBackupResponse[]>()
  const [selected, setSelected] = useState<Record<string, boolean>>({})
  const [restoringIds, setRestoringIds] = useState<Record<string, boolean>>({})

  function reload() {
    orgs.call(() => raum.orgs.list(token))
    backups.call(() => raum.drBackups.list(token))
  }
  useEffect(reload, [token])

  const orgNames = orgs.state.status === 'success'
    ? Object.fromEntries(orgs.state.data.map(o => [o.id, o.name] as const))
    : {}
  const backupList = backups.state.status === 'success' ? backups.state.data.filter(b => b.restorable) : []

  const groups = useMemo<BackupGroup[]>(() => {
    const map = new Map<string, BackupGroup>()
    for (const b of backupList) {
      const ts = new Date(b.createdAt).getTime()
      const day = new Date(b.createdAt).toLocaleDateString()
      const key = b.scope === 'ORG' ? `org:${b.orgId}:${day}` : `instance:${day}`
      const label = b.scope === 'ORG' ? (orgNames[b.orgId ?? ''] ?? t('drBackupsPage.unknownOrg')) : t('drBackupsPage.instanceGroup')
      let group = map.get(key)
      if (!group) {
        group = { key, label, dateLabel: day, backups: [], latestTs: ts }
        map.set(key, group)
      }
      group.backups.push(b)
      if (ts > group.latestTs) group.latestTs = ts
    }
    for (const group of map.values()) {
      group.backups.sort((a, b) => rowLabel(a).localeCompare(rowLabel(b)))
    }
    return Array.from(map.values()).sort((a, b) => b.latestTs - a.latestTs)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [backupList, orgNames])

  function toggleRow(id: string) {
    setSelected(prev => ({ ...prev, [id]: !prev[id] }))
  }

  function toggleGroupAll(group: BackupGroup) {
    const allSelected = group.backups.every(b => selected[b.id])
    setSelected(prev => {
      const next = { ...prev }
      for (const b of group.backups) next[b.id] = !allSelected
      return next
    })
  }

  async function restoreGroupSelection(group: BackupGroup) {
    const ids = group.backups.filter(b => selected[b.id]).map(b => b.id)
    if (ids.length === 0) return
    const target = `${group.label} · ${group.dateLabel}`
    if (!window.confirm(t('drBackupsPage.restoreConfirm', { count: ids.length, target }))) return

    setRestoringIds(prev => {
      const next = { ...prev }
      for (const id of ids) next[id] = true
      return next
    })
    const results = await Promise.allSettled(ids.map(id => raum.drBackups.restore(id, token)))
    setRestoringIds(prev => {
      const next = { ...prev }
      for (const id of ids) delete next[id]
      return next
    })
    setSelected(prev => {
      const next = { ...prev }
      for (const id of ids) delete next[id]
      return next
    })

    const failed = results.filter(r => r.status === 'rejected').length
    const succeeded = ids.length - failed
    if (failed === 0) {
      toast.show(t('drBackupsPage.restoredCount', { count: succeeded }))
    } else {
      toast.show(t('drBackupsPage.restorePartialFailure', { succeeded, failed }), 'error')
    }
  }

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>{t('drBackupsPage.title')}</h1>
          <p>{t('drBackupsPage.subtitle')}</p>
        </div>
        <button className="btn btn-outline" onClick={reload} type="button">{t('drBackupsPage.refresh')}</button>
      </div>

      {backups.state.status === 'error' && <Feedback state={backups.state} />}

      {backups.state.status === 'success' && groups.length === 0 && (
        <div className="panel"><div className="empty-state">{t('drBackupsPage.emptyState')}</div></div>
      )}

      {groups.map(group => {
        const selectedCount = group.backups.filter(b => selected[b.id]).length
        const allSelected = selectedCount > 0 && selectedCount === group.backups.length
        const groupBusy = group.backups.some(b => restoringIds[b.id])
        return (
          <div className="panel backup-group" key={group.key}>
            <div className="backup-group-header">
              <div className="backup-group-title">
                <label className="field-checkbox">
                  <input type="checkbox" checked={allSelected} onChange={() => toggleGroupAll(group)} />
                </label>
                <span>{group.label}</span>
                <span className="backup-group-date">{group.dateLabel}</span>
              </div>
              <div className="backup-group-actions">
                {selectedCount > 0 && <span className="backup-group-selected">{t('drBackupsPage.selectedCount', { count: selectedCount })}</span>}
                <button
                  className="btn btn-danger"
                  type="button"
                  disabled={selectedCount === 0 || groupBusy}
                  onClick={() => restoreGroupSelection(group)}
                >
                  {groupBusy ? t('common.actions.loading') : t('drBackupsPage.restoreSelected')}
                </button>
              </div>
            </div>
            <div className="backup-checklist">
              {group.backups.map(b => (
                <label className="backup-row" key={b.id}>
                  <input
                    type="checkbox"
                    checked={!!selected[b.id]}
                    disabled={!!restoringIds[b.id]}
                    onChange={() => toggleRow(b.id)}
                  />
                  <span className="backup-row-service">{rowLabel(b)}</span>
                  <span className="backup-row-meta">{new Date(b.createdAt).toLocaleTimeString()}</span>
                </label>
              ))}
            </div>
          </div>
        )
      })}
    </div>
  )
}
