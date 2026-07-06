import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { vassago } from '../api/vassago'
import { raum } from '../api/raum'
import { useApiCall } from '../hooks/useApiCall'
import { useToast } from '../components/Toast'
import { Modal } from '../components/Modal'
import { Tabs } from '../components/Tabs'
import { DataTable, type Column } from '../components/DataTable'
import { RowActionsMenu } from '../components/RowActionsMenu'
import { Combobox } from '../components/Combobox'
import { CopyButton } from '../components/CopyButton'
import { Feedback } from '../components/Feedback'
import type { Permissions } from '../auth'
import type { ServiceResponse, UserRequest, UserResponse } from '../types'

interface Props {
  token: string
  permissions: Permissions
}

interface RoleRow {
  serviceId: string
  role: string
}

const KNOWN_ROLES = ['VASSAGO_ADMIN', 'VASSAGO_USER', 'RAUM_ADMIN', 'RAUM_ONBOARDING']
const ROLE_ITEMS = KNOWN_ROLES.map(r => ({ id: r, label: r }))

function buildRolesMap(rows: RoleRow[]): Record<string, string[]> {
  const map: Record<string, string[]> = {}
  for (const { serviceId, role } of rows) {
    if (!serviceId || !role) continue
    if (!map[serviceId]) map[serviceId] = []
    map[serviceId].push(role)
  }
  return map
}

function parseRolesMap(roles: Record<string, string[]>): RoleRow[] {
  return Object.entries(roles ?? {}).flatMap(([svcId, roleList]) =>
    roleList.map(role => ({ serviceId: svcId, role }))
  )
}

function RolesInput({ value, onChange, services }: { value: RoleRow[]; onChange: (rows: RoleRow[]) => void; services: ServiceResponse[] }) {
  const { t } = useTranslation()
  const serviceItems = services.map(s => ({ id: s.id, label: s.name }))
  return (
    <div className="roles-input">
      {value.map((row, i) => (
        <div key={i} className="role-row">
          <Combobox
            items={serviceItems}
            value={row.serviceId || null}
            onChange={id => onChange(value.map((r, j) => j === i ? { ...r, serviceId: id ?? '' } : r))}
            placeholder={t('usersPage.servicePlaceholder')}
          />
          <Combobox
            items={ROLE_ITEMS}
            value={row.role || null}
            onChange={id => onChange(value.map((r, j) => j === i ? { ...r, role: id ?? '' } : r))}
            placeholder={t('usersPage.rolePlaceholder')}
          />
          <button className="btn btn-outline btn-sm" onClick={() => onChange(value.filter((_, j) => j !== i))} type="button">−</button>
        </div>
      ))}
      <button className="btn btn-outline btn-sm" onClick={() => onChange([...value, { serviceId: '', role: '' }])} type="button">
        {t('usersPage.addRole')}
      </button>
    </div>
  )
}

const EMPTY_USER_FORM: Omit<UserRequest, 'roles'> = { email: '', name: '', lastName: '', username: '' }

export default function UsersPage({ token, permissions }: Props) {
  const { t } = useTranslation()
  const toast = useToast()

  const [services, setServices] = useState<ServiceResponse[]>([])
  useEffect(() => { raum.services.list(token).then(setServices).catch(() => {}) }, [token])

  const list = useApiCall<UserResponse[]>()
  function reload() { list.call(() => vassago.users.list(token)) }
  useEffect(reload, [token])
  const users = list.state.status === 'success' ? list.state.data : []

  const [activeTab, setActiveTab] = useState('users')

  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<UserResponse | null>(null)
  const [form, setForm] = useState(EMPTY_USER_FORM)
  const [roles, setRoles] = useState<RoleRow[]>([])
  const save = useApiCall<UserResponse>()
  const offboard = useApiCall<void>()

  useEffect(() => {
    if (save.state.status !== 'success') return
    setModalOpen(false)
    reload()
    toast.show(t(editing ? 'usersPage.updated' : 'usersPage.created'))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [save.state])

  function openCreate() {
    setEditing(null)
    setForm(EMPTY_USER_FORM)
    setRoles([])
    setModalOpen(true)
  }

  function openEdit(user: UserResponse) {
    setEditing(user)
    setForm({ email: user.email, name: user.name, lastName: user.lastName, username: user.username })
    setRoles(parseRolesMap(user.roles))
    setModalOpen(true)
  }

  function submit() {
    const dto = { ...form, roles: buildRolesMap(roles) }
    save.call(() => editing ? vassago.users.update(editing.id, dto, token) : vassago.users.create(dto, token))
  }

  function remove(user: UserResponse) {
    if (!window.confirm(t('usersPage.offboardConfirm', { name: `${user.name} ${user.lastName}` }))) return
    offboard.call(() => vassago.users.offboard(user.id, token)).then(() => {
      reload()
      toast.show(t('usersPage.offboarded'))
    })
  }

  const columns: Column<UserResponse>[] = [
    { key: 'name', header: t('usersPage.firstName'), render: u => `${u.name} ${u.lastName}`, sortValue: u => u.name },
    { key: 'username', header: t('usersPage.username'), render: u => <span className="td-muted">@{u.username}</span> },
    { key: 'email', header: t('usersPage.email'), render: u => <span className="td-muted">{u.email}</span> },
    {
      key: 'roles',
      header: t('usersPage.roles'),
      render: u => (
        <div className="role-chips">
          {Object.values(u.roles ?? {}).flat().map((r, i) => <span key={i} className="role-badge">{r}</span>)}
        </div>
      ),
    },
    {
      key: 'actions',
      header: '',
      render: u => (
        <RowActionsMenu actions={[
          ...(permissions.canEditUsers ? [{ label: t('common.actions.edit'), onClick: () => openEdit(u) }] : []),
          ...(permissions.canOffboardUsers ? [{ label: t('common.actions.delete'), onClick: () => remove(u), danger: true }] : []),
        ]} />
      ),
    },
  ]

  const changePassword = useApiCall<void>()
  const [oldPassword, setOldPassword] = useState('')
  const publicKey = useApiCall<{ publicKey: string }>()

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>{t('usersPage.title')}</h1>
          <p>{t('usersPage.subtitle')}</p>
        </div>
      </div>

      <Tabs
        tabs={[
          { id: 'users', label: t('usersPage.tabUsers') },
          { id: 'account', label: t('usersPage.tabAccount') },
        ]}
        active={activeTab}
        onChange={setActiveTab}
      >
        {activeTab === 'users' && (
          <div className="panel">
            {list.state.status === 'error' && <Feedback state={list.state} />}
            <DataTable
              columns={columns}
              rows={users}
              rowKey={u => u.id}
              searchable
              searchText={u => `${u.name} ${u.lastName} ${u.username} ${u.email}`}
              onRowClick={permissions.canEditUsers ? openEdit : undefined}
              emptyLabel={t('usersPage.emptyState')}
              headerAction={permissions.canCreateUsers
                ? <button className="btn btn-primary" onClick={openCreate} type="button">{t('usersPage.createAction')}</button>
                : undefined}
            />
          </div>
        )}

        {activeTab === 'account' && (
          <>
            <div className="panel">
              <h2>{t('usersPage.changePasswordTitle')}</h2>
              <p className="panel-hint">{t('usersPage.changePasswordHint')}</p>
              <div className="fields">
                <div className="field">
                  <label>{t('usersPage.currentPassword')}</label>
                  <input type="password" value={oldPassword} onChange={e => setOldPassword(e.target.value)} autoComplete="current-password" />
                </div>
              </div>
              <div className="actions">
                <button
                  className="btn btn-outline"
                  disabled={changePassword.state.status === 'loading' || !oldPassword}
                  onClick={() => changePassword.call(() => vassago.users.changePassword(oldPassword, token))}
                >
                  {changePassword.state.status === 'loading' ? t('usersPage.sending') : t('usersPage.sendResetLink')}
                </button>
              </div>
              <Feedback state={changePassword.state} successLabel={t('usersPage.resetLinkSent')} />
            </div>

            <div className="panel">
              <h2>{t('usersPage.publicKeyTitle')}</h2>
              <p className="panel-hint">{t('usersPage.publicKeyHint')}</p>
              <div className="actions">
                <button className="btn btn-outline" disabled={publicKey.state.status === 'loading'} onClick={() => publicKey.call(() => vassago.publicKey())}>
                  {publicKey.state.status === 'loading' ? t('usersPage.fetching') : t('usersPage.fetch')}
                </button>
              </div>
              {publicKey.state.status === 'success' && (
                <div className="pubkey-block">
                  <div className="pubkey-actions">
                    <CopyButton text={publicKey.state.data.publicKey} />
                  </div>
                  <pre className="pubkey-pre">{publicKey.state.data.publicKey}</pre>
                </div>
              )}
              {publicKey.state.status === 'error' && <Feedback state={publicKey.state} />}
            </div>
          </>
        )}
      </Tabs>

      <Modal open={modalOpen} onClose={() => setModalOpen(false)} title={t(editing ? 'usersPage.editTitle' : 'usersPage.createTitle')}>
        <div className="fields">
          <div className="field">
            <label>{t('usersPage.firstName')}</label>
            <input value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} placeholder="Ada" />
          </div>
          <div className="field">
            <label>{t('usersPage.lastName')}</label>
            <input value={form.lastName} onChange={e => setForm(f => ({ ...f, lastName: e.target.value }))} placeholder="Lovelace" />
          </div>
          <div className="field">
            <label>{t('usersPage.email')}</label>
            <input type="email" value={form.email} onChange={e => setForm(f => ({ ...f, email: e.target.value }))} placeholder="ada@example.com" />
          </div>
          <div className="field">
            <label>{t('usersPage.username')}</label>
            <input value={form.username} onChange={e => setForm(f => ({ ...f, username: e.target.value }))} placeholder="ada" autoComplete="off" />
          </div>
        </div>
        <div className="field" style={{ marginBottom: '14px' }}>
          <label style={{ marginBottom: '8px' }}>{t('usersPage.roles')}</label>
          <RolesInput value={roles} onChange={setRoles} services={services} />
        </div>
        <div className="actions">
          <button
            className="btn btn-primary"
            disabled={save.state.status === 'loading' || !form.email || !form.name || !form.username}
            onClick={submit}
          >
            {save.state.status === 'loading' ? t('common.actions.loading') : t(editing ? 'common.actions.save' : 'common.actions.create')}
          </button>
        </div>
        {save.state.status === 'error' && <Feedback state={save.state} />}
        {editing && (
          <details className="id-disclosure">
            <summary>{t('common.fields.id')}</summary>
            <div className="id-disclosure-row">
              <span className="id-disclosure-value">{editing.id}</span>
              <CopyButton text={editing.id} />
            </div>
          </details>
        )}
      </Modal>
    </div>
  )
}
