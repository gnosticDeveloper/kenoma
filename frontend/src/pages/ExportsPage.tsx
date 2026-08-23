import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { raum } from '../api/raum'
import { useApiCall } from '../hooks/useApiCall'
import { Modal } from '../components/Modal'
import { DataTable, type Column } from '../components/DataTable'
import { RowActionsMenu } from '../components/RowActionsMenu'
import { Feedback } from '../components/Feedback'
import { ExportsIcon } from '../components/icons'
import type { ExportDownloadResponse, ExportFilePart, ExportJobResponse, OrgResponse } from '../types'

function fileName(key: string): string {
  return key.split('/').pop() ?? key
}

async function triggerDownload(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = name
  a.click()
  URL.revokeObjectURL(url)
}

interface DownloadFileListProps {
  orgId: string
  jobId: string
  files: ExportFilePart[]
  token: string
}

export function DownloadFileList({ orgId, jobId, files, token }: DownloadFileListProps) {
  async function download(f: ExportFilePart) {
    const blob = await raum.orgs.downloadExportFile(orgId, jobId, f.index, token)
    triggerDownload(blob, fileName(f.key))
  }

  return (
    <div className="download-file-list">
      {files.map(f => (
        <button key={f.key} type="button" className="btn btn-outline" title={f.key} onClick={() => download(f)}>
          <ExportsIcon /> {fileName(f.key)}
        </button>
      ))}
    </div>
  )
}

interface Props { token: string }

const ACTIVE_EXPORT_STATUSES = ['PENDING', 'RUNNING']

export default function ExportsPage({ token }: Props) {
  const { t } = useTranslation()

  const orgs = useApiCall<OrgResponse[]>()
  const jobs = useApiCall<ExportJobResponse[]>()

  function reload() {
    orgs.call(() => raum.orgs.list(token))
    jobs.call(() => raum.exportJobs.list(token))
  }
  useEffect(reload, [token])

  const orgNames = orgs.state.status === 'success'
    ? Object.fromEntries(orgs.state.data.map(o => [o.id, o.name] as const))
    : {}
  const jobList = jobs.state.status === 'success' ? jobs.state.data : []
  const hasActiveJob = jobList.some(j => ACTIVE_EXPORT_STATUSES.includes(j.status))

  // Auto-refreshes the job list every 5s while anything is still queued/running.
  useEffect(() => {
    if (!hasActiveJob) return
    const timer = setTimeout(() => jobs.call(() => raum.exportJobs.list(token)), 5000)
    return () => clearTimeout(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [jobs.state, hasActiveJob])

  const [downloadTarget, setDownloadTarget] = useState<ExportJobResponse | null>(null)
  const downloadLinks = useApiCall<ExportDownloadResponse>()

  function openDownload(job: ExportJobResponse) {
    setDownloadTarget(job)
    downloadLinks.call(() => raum.orgs.getExportDownloadLinks(job.orgId, job.id, token))
  }

  const [errorTarget, setErrorTarget] = useState<ExportJobResponse | null>(null)

  const columns: Column<ExportJobResponse>[] = [
    { key: 'org', header: t('exportsPage.org'), render: j => orgNames[j.orgId] ?? t('exportsPage.unknownOrg'), sortValue: j => orgNames[j.orgId] ?? '' },
    { key: 'format', header: t('exportsPage.format'), render: j => j.format },
    { key: 'layout', header: t('exportsPage.layout'), render: j => t(`orgsPage.layoutValue.${j.layout}`) },
    {
      key: 'status',
      header: t('exportsPage.status'),
      render: j => (
        <span className={`status-badge ${j.status === 'DONE' ? 'status-ok' : j.status === 'FAILED' ? 'status-fail' : ''}`}>
          {t(`orgsPage.exportStatusValue.${j.status}`)}
        </span>
      ),
      sortValue: j => j.status,
    },
    { key: 'requestedAt', header: t('exportsPage.requestedAt'), render: j => new Date(j.requestedAt).toLocaleString(), sortValue: j => j.requestedAt },
    { key: 'completedAt', header: t('exportsPage.completedAt'), render: j => j.completedAt ? new Date(j.completedAt).toLocaleString() : '—' },
    {
      key: 'actions',
      header: '',
      render: j => (
        <RowActionsMenu actions={[
          ...(j.status === 'DONE' ? [{ label: t('exportsPage.download'), onClick: () => openDownload(j) }] : []),
          ...(j.status === 'FAILED' ? [{ label: t('exportsPage.viewError'), onClick: () => setErrorTarget(j) }] : []),
        ]} />
      ),
    },
  ]

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>{t('exportsPage.title')}</h1>
          <p>{t('exportsPage.subtitle')}</p>
        </div>
      </div>

      <div className="panel">
        {jobs.state.status === 'error' && <Feedback state={jobs.state} />}
        <DataTable
          columns={columns}
          rows={jobList}
          rowKey={j => j.id}
          searchable
          searchText={j => `${orgNames[j.orgId] ?? ''} ${j.format} ${j.status}`}
          emptyLabel={t('exportsPage.emptyState')}
          headerAction={<button className="btn btn-outline" onClick={reload} type="button">{t('exportsPage.refresh')}</button>}
        />
      </div>

      <Modal
        open={downloadTarget !== null}
        onClose={() => setDownloadTarget(null)}
        title={downloadTarget ? t('orgsPage.exportTitle', { name: orgNames[downloadTarget.orgId] ?? t('exportsPage.unknownOrg') }) : ''}
      >
        {downloadLinks.state.status === 'loading' && <p className="panel-hint">{t('common.actions.loading')}</p>}
        {downloadLinks.state.status === 'error' && <Feedback state={downloadLinks.state} />}
        {downloadLinks.state.status === 'success' && downloadLinks.state.data.files.length === 0 && (
          <p className="panel-hint">{t('exportsPage.noFilesRecorded')}</p>
        )}
        {downloadLinks.state.status === 'success' && downloadLinks.state.data.files.length > 0 && downloadTarget && (
          <div className="field">
            <label>{t('orgsPage.exportDownloadFiles')}</label>
            <DownloadFileList orgId={downloadTarget.orgId} jobId={downloadTarget.id} files={downloadLinks.state.data.files} token={token} />
          </div>
        )}
      </Modal>

      <Modal
        open={errorTarget !== null}
        onClose={() => setErrorTarget(null)}
        title={errorTarget ? t('orgsPage.exportTitle', { name: orgNames[errorTarget.orgId] ?? t('exportsPage.unknownOrg') }) : ''}
      >
        <p className="panel-hint">{errorTarget?.errorMessage}</p>
      </Modal>
    </div>
  )
}
