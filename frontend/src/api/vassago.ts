import type { LoginRequest, RoleResponse, UserRequest, UserResponse } from '../types'
import { req } from './client'

export const vassago = {
  login: (dto: LoginRequest) =>
    req<{ token: string }>('/auth/login', { method: 'POST', body: JSON.stringify(dto) }),
  refresh: () =>
    req<{ token: string }>('/auth/refresh', { method: 'POST' }),
  logout: (token: string) =>
    req<void>('/auth/logout', { method: 'POST' }, token),
  publicKey: () =>
    req<{ publicKey: string }>('/auth/public-key', { method: 'GET' }),
  recover: (dto: { orgId: string; username: string }) =>
    req<void>('/auth/recover', { method: 'POST', body: JSON.stringify(dto) }),
  roles: (token: string) =>
    req<RoleResponse[]>('/roles/vassago', { method: 'GET' }, token),

  users: {
    list: (token: string) =>
      req<UserResponse[]>('/user', { method: 'GET' }, token),
    get: (id: string, token: string) =>
      req<UserResponse>(`/user/${id}`, { method: 'GET' }, token),
    create: (dto: UserRequest, token: string) =>
      req<UserResponse>('/user', { method: 'POST', body: JSON.stringify(dto) }, token),
    update: (id: string, dto: UserRequest, token: string) =>
      req<UserResponse>(`/user/${id}`, { method: 'PUT', body: JSON.stringify(dto) }, token),
    offboard: (id: string, token: string) =>
      req<void>(`/user/${id}`, { method: 'DELETE' }, token),
    verify: (dto: { orgId: string; token: string; newPassword: string }) =>
      req<void>('/user/verify', { method: 'POST', body: JSON.stringify(dto) }),
    changePassword: (oldPassword: string, token: string) =>
      req<void>('/user/password', { method: 'PATCH', body: JSON.stringify({ oldPassword }) }, token),
  },
}
