import { useEffect, useState } from 'react'
import { vassago } from '../api/vassago'
import { raum } from '../api/raum'
import { useApiCall } from '../hooks/useApiCall'
import { Feedback } from '../components/Feedback'
import { UserCard } from '../components/UserCard'
import { UserTable } from '../components/UserTable'
import { CopyButton } from '../components/CopyButton'
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

function buildRolesMap(rows: RoleRow[]): Record<string, string[]> {
  const map: Record<string, string[]> = {}
  for (const { serviceId, role } of rows) {
    const svc = serviceId.trim()
    const r = role.trim()
    if (!svc || !r) continue
    if (!map[svc]) map[svc] = []
    map[svc].push(r)
  }
  return map
}

function parseRolesMap(roles: Record<string, string[]>): RoleRow[] {
  return Object.entries(roles ?? {}).flatMap(([svcId, roleList]) =>
    roleList.map(role => ({ serviceId: svcId, role }))
  )
}

function RolesInput({ value, onChange, services }: { value: RoleRow[]; onChange: (rows: RoleRow[]) => void; services: ServiceResponse[] }) {
  return (
    <div className="roles-input">
      <datalist id="known-roles">
        {KNOWN_ROLES.map(r => <option key={r} value={r} />)}
      </datalist>
      {value.map((row, i) => (
        <div key={i} className="role-row">
          <select
            value={row.serviceId}
            onChange={e => onChange(value.map((r, j) => j === i ? { ...r, serviceId: e.target.value } : r))}
            className="role-row-svc"
          >
            <option value="">Select a service…</option>
            {services.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
          </select>
          <input
            list="known-roles"
            value={row.role}
            onChange={e => onChange(value.map((r, j) => j === i ? { ...r, role: e.target.value } : r))}
            placeholder="Role"
            className="role-row-name"
          />
          <button
            className="btn btn-outline btn-sm"
            onClick={() => onChange(value.filter((_, j) => j !== i))}
            type="button"
          >
            −
          </button>
        </div>
      ))}
      <button
        className="btn btn-outline btn-sm"
        onClick={() => onChange([...value, { serviceId: '', role: '' }])}
        type="button"
      >
        + Add role
      </button>
    </div>
  )
}

const EMPTY_USER_FORM: Omit<UserRequest, 'roles'> = {
  email: '', name: '', lastName: '', username: '',
}

export default function UsersPage({ token, permissions }: Props) {
  // Services, loaded once to power the role-assignment dropdowns.
  const [services, setServices] = useState<ServiceResponse[]>([])
  useEffect(() => {
    raum.services.list(token).then(setServices).catch(() => {})
  }, [token])

  // All Users
  const list = useApiCall<UserResponse[]>()

  // Create User
  const create = useApiCall<UserResponse>()
  const [createForm, setCreateForm] = useState(EMPTY_USER_FORM)
  const [createRoles, setCreateRoles] = useState<RoleRow[]>([])

  // Look Up User
  const lookup = useApiCall<UserResponse>()
  const [lookupId, setLookupId] = useState('')

  // Update User
  const update = useApiCall<UserResponse>()
  const [updateId, setUpdateId] = useState('')
  const [updateForm, setUpdateForm] = useState(EMPTY_USER_FORM)
  const [updateRoles, setUpdateRoles] = useState<RoleRow[]>([])

  // Offboard User
  const offboard = useApiCall<void>()
  const [offboardId, setOffboardId] = useState('')

  // Change Password
  const changePassword = useApiCall<void>()
  const [oldPassword, setOldPassword] = useState('')

  // Public Key
  const publicKey = useApiCall<{ publicKey: string }>()

  function loadIntoEditForm(user: UserResponse) {
    setUpdateId(user.id)
    setUpdateForm({ email: user.email, name: user.name, lastName: user.lastName, username: user.username })
    setUpdateRoles(parseRolesMap(user.roles))
  }

  return (
    <div className="page">

      {/* ── All Users ── */}
      <div className="panel">
        <h2>All Users</h2>
        <div className="actions">
          <button
            className="btn btn-outline"
            disabled={list.state.status === 'loading'}
            onClick={() => list.call(() => vassago.users.list(token))}
          >
            {list.state.status === 'loading' ? 'Loading…' : 'Load'}
          </button>
        </div>
        {list.state.status === 'success' && <UserTable users={list.state.data} />}
        {list.state.status === 'error' && <div className="error">{list.state.message}</div>}
      </div>

      {/* ── Create User ── */}
      {permissions.canCreateUsers && (
        <div className="panel">
          <h2>Create User</h2>
          <div className="fields">
            <div className="field">
              <label>First Name</label>
              <input value={createForm.name} onChange={e => setCreateForm(f => ({ ...f, name: e.target.value }))} placeholder="Ada" />
            </div>
            <div className="field">
              <label>Last Name</label>
              <input value={createForm.lastName} onChange={e => setCreateForm(f => ({ ...f, lastName: e.target.value }))} placeholder="Lovelace" />
            </div>
            <div className="field">
              <label>Email</label>
              <input type="email" value={createForm.email} onChange={e => setCreateForm(f => ({ ...f, email: e.target.value }))} placeholder="ada@example.com" />
            </div>
            <div className="field">
              <label>Username</label>
              <input value={createForm.username} onChange={e => setCreateForm(f => ({ ...f, username: e.target.value }))} placeholder="ada" autoComplete="off" />
            </div>
          </div>
          <div className="field" style={{ marginBottom: '14px' }}>
            <label style={{ marginBottom: '8px' }}>Roles</label>
            <RolesInput value={createRoles} onChange={setCreateRoles} services={services} />
          </div>
          <div className="actions">
            <button
              className="btn btn-primary"
              disabled={create.state.status === 'loading' || !createForm.email || !createForm.name || !createForm.username}
              onClick={() => create.call(() => vassago.users.create(
                { ...createForm, roles: buildRolesMap(createRoles) },
                token,
              ))}
            >
              {create.state.status === 'loading' ? 'Creating…' : 'Create'}
            </button>
          </div>
          {create.state.status === 'success' && <UserCard user={create.state.data} />}
          {create.state.status === 'error' && <div className="error">{create.state.message}</div>}
        </div>
      )}

      {/* ── Look Up User ── */}
      <div className="panel">
        <h2>Look Up User</h2>
        <div className="fields">
          <div className="field">
            <label>User ID</label>
            <input
              value={lookupId}
              onChange={e => setLookupId(e.target.value)}
              placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
              spellCheck={false}
            />
          </div>
        </div>
        <div className="actions">
          <button
            className="btn btn-outline"
            disabled={lookup.state.status === 'loading' || !lookupId.trim()}
            onClick={() => lookup.call(() => vassago.users.get(lookupId.trim(), token))}
          >
            {lookup.state.status === 'loading' ? 'Fetching…' : 'Fetch'}
          </button>
        </div>
        {lookup.state.status === 'success' && (() => {
          const user = lookup.state.data
          return (
            <>
              <UserCard user={user} />
              {permissions.canEditUsers && (
                <div style={{ marginTop: '8px' }}>
                  <button
                    className="btn btn-outline btn-sm"
                    onClick={() => loadIntoEditForm(user)}
                  >
                    Load into edit form ↓
                  </button>
                </div>
              )}
            </>
          )
        })()}
        {lookup.state.status === 'error' && <div className="error">{lookup.state.message}</div>}
      </div>

      {/* ── Update User ── */}
      {permissions.canEditUsers && (
        <div className="panel">
          <h2>Update User</h2>
          <div className="fields">
            <div className="field">
              <label>User ID</label>
              <input
                value={updateId}
                onChange={e => setUpdateId(e.target.value)}
                placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
                spellCheck={false}
              />
            </div>
            <div className="field">
              <label>First Name</label>
              <input value={updateForm.name} onChange={e => setUpdateForm(f => ({ ...f, name: e.target.value }))} />
            </div>
            <div className="field">
              <label>Last Name</label>
              <input value={updateForm.lastName} onChange={e => setUpdateForm(f => ({ ...f, lastName: e.target.value }))} />
            </div>
            <div className="field">
              <label>Email</label>
              <input type="email" value={updateForm.email} onChange={e => setUpdateForm(f => ({ ...f, email: e.target.value }))} />
            </div>
            <div className="field">
              <label>Username</label>
              <input value={updateForm.username} onChange={e => setUpdateForm(f => ({ ...f, username: e.target.value }))} autoComplete="off" />
            </div>
          </div>
          <div className="field" style={{ marginBottom: '14px' }}>
            <label style={{ marginBottom: '8px' }}>Roles</label>
            <RolesInput value={updateRoles} onChange={setUpdateRoles} services={services} />
          </div>
          <div className="actions">
            <button
              className="btn btn-primary"
              disabled={update.state.status === 'loading' || !updateId.trim() || !updateForm.email}
              onClick={() => update.call(() => vassago.users.update(
                updateId.trim(),
                { ...updateForm, roles: buildRolesMap(updateRoles) },
                token,
              ))}
            >
              {update.state.status === 'loading' ? 'Updating…' : 'Update'}
            </button>
          </div>
          {update.state.status === 'success' && <UserCard user={update.state.data} />}
          {update.state.status === 'error' && <div className="error">{update.state.message}</div>}
        </div>
      )}

      {/* ── Offboard User ── */}
      {permissions.canOffboardUsers && (
        <div className="panel">
          <h2>Offboard User</h2>
          <p className="panel-hint">Permanently removes the user. This cannot be undone.</p>
          <div className="fields">
            <div className="field">
              <label>User ID</label>
              <input
                value={offboardId}
                onChange={e => setOffboardId(e.target.value)}
                placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
                spellCheck={false}
              />
            </div>
          </div>
          <div className="actions">
            <button
              className="btn btn-danger"
              disabled={offboard.state.status === 'loading' || !offboardId.trim()}
              onClick={() => {
                if (!window.confirm(`Offboard user ${offboardId.trim()}? This cannot be undone.`)) return
                offboard.call(() => vassago.users.offboard(offboardId.trim(), token))
              }}
            >
              {offboard.state.status === 'loading' ? 'Offboarding…' : 'Offboard'}
            </button>
          </div>
          <Feedback state={offboard.state} successLabel="User offboarded." />
        </div>
      )}

      {/* ── Change Password ── */}
      <div className="panel">
        <h2>Change Password</h2>
        <p className="panel-hint">Verifies your current password then sends a reset link to your registered email.</p>
        <div className="fields">
          <div className="field">
            <label>Current Password</label>
            <input
              type="password"
              value={oldPassword}
              onChange={e => setOldPassword(e.target.value)}
              autoComplete="current-password"
            />
          </div>
        </div>
        <div className="actions">
          <button
            className="btn btn-outline"
            disabled={changePassword.state.status === 'loading' || !oldPassword}
            onClick={() => changePassword.call(() => vassago.users.changePassword(oldPassword, token))}
          >
            {changePassword.state.status === 'loading' ? 'Sending…' : 'Send Reset Link'}
          </button>
        </div>
        <Feedback state={changePassword.state} successLabel="Password reset link sent." />
      </div>

      {/* ── Public Key ── */}
      <div className="panel">
        <h2>JWT Public Key</h2>
        <p className="panel-hint">The ES256 public key used to verify tokens issued by this Vassago instance.</p>
        <div className="actions">
          <button
            className="btn btn-outline"
            disabled={publicKey.state.status === 'loading'}
            onClick={() => publicKey.call(() => vassago.publicKey())}
          >
            {publicKey.state.status === 'loading' ? 'Fetching…' : 'Fetch'}
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
        {publicKey.state.status === 'error' && <div className="error">{publicKey.state.message}</div>}
      </div>

    </div>
  )
}
