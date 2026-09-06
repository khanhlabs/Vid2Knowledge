import { api } from '../../shared/api/client'

export interface LegalPolicy {
  type: 'TERMS' | 'PRIVACY' | 'ACCEPTABLE_USE' | 'AI_NOTICE'
  version: string
  url: string
}

export interface LegalStatus {
  accepted: boolean
  manifest: {
    policySetVersion: string
    reviewed: boolean
    policies: LegalPolicy[]
  }
}

export const legalApi = {
  status: () => api<LegalStatus>('/api/v1/legal/status'),
  accept: (status: LegalStatus) => {
    const versions = Object.fromEntries(
      status.manifest.policies.map((policy) => [policy.type, policy.version]),
    ) as Record<LegalPolicy['type'], string>
    return api<LegalStatus>('/api/v1/legal/acceptances', {
      method: 'POST',
      body: JSON.stringify({
        policySetVersion: status.manifest.policySetVersion,
        termsVersion: versions.TERMS,
        privacyVersion: versions.PRIVACY,
        acceptableUseVersion: versions.ACCEPTABLE_USE,
        aiNoticeVersion: versions.AI_NOTICE,
        confirmed: true,
      }),
    })
  },
}
