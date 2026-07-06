import type {
  BasicCredential,
  Credentials,
  OrgRequest,
  OrgResponse,
  OnboardingRequest,
  ServiceRequest,
  ServiceResponse,
} from '../types'
import { payload, req } from './client'

export const raum = {
  orgs: {
    create: (dto: OrgRequest, token: string) =>
      req<OrgResponse>('/orgs', { method: 'POST', ...payload(dto) }, token),
    list: (token: string) =>
      req<OrgResponse[]>('/orgs', { method: 'GET' }, token),
    get: (id: string, token: string) =>
      req<OrgResponse>(`/orgs/${id}`, { method: 'GET' }, token),
    update: (id: string, dto: OrgRequest, token: string) =>
      req<OrgResponse>(`/orgs/${id}`, { method: 'PUT', ...payload(dto) }, token),
    delete: (id: string, token: string) =>
      req<void>(`/orgs/${id}`, { method: 'DELETE' }, token),
  },
  services: {
    create: (dto: ServiceRequest, token: string) =>
      req<ServiceResponse>('/services', { method: 'POST', ...payload(dto) }, token),
    list: (token: string) =>
      req<ServiceResponse[]>('/services', { method: 'GET' }, token),
    get: (id: string, token: string) =>
      req<ServiceResponse>(`/services/${id}`, { method: 'GET' }, token),
    update: (id: string, dto: ServiceRequest, token: string) =>
      req<ServiceResponse>(`/services/${id}`, { method: 'PUT', ...payload(dto) }, token),
    delete: (id: string, token: string) =>
      req<void>(`/services/${id}`, { method: 'DELETE' }, token),
  },
  credentials: {
    register: (dto: Credentials, token: string) =>
      req<BasicCredential>('/credentials', { method: 'POST', ...payload(dto) }, token),
    ephemeral: (dto: BasicCredential, token: string) =>
      req<Credentials>('/credentials/ephemeral', { method: 'POST', ...payload(dto) }, token),
    testDb: (token: string) =>
      req<boolean>('/credentials/test-db', { method: 'GET' }, token),
  },
  onboarding: {
    initiate: (orgId: string, dto: OnboardingRequest, token: string) =>
      req<void>(`/onboarding/${orgId}`, { method: 'POST', ...payload(dto) }, token),
  },
}
