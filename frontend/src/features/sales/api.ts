import { api, idempotencyKey } from '../../shared/api/client'

export interface PilotLeadRequest {
  contactName: string
  workEmail: string
  organizationName: string
  buyerRole:
    'OWNER' | 'TRAINING_MANAGER' | 'INSTRUCTOR' | 'OPERATIONS' | 'OTHER'
  monthlyVideoMinutes:
    | 'UNDER_100'
    | 'BETWEEN_100_299'
    | 'BETWEEN_300_599'
    | 'BETWEEN_600_1499'
    | 'OVER_1500'
  learnerCount:
    | 'UNDER_50'
    | 'BETWEEN_50_199'
    | 'BETWEEN_200_499'
    | 'BETWEEN_500_999'
    | 'OVER_1000'
  primaryGoal:
    | 'SAVE_AUTHORING_TIME'
    | 'IMPROVE_COMPLETION'
    | 'PROVE_LEARNING'
    | 'SCALE_COHORTS'
    | 'OTHER'
  note: string
  acquisitionSource:
    'DIRECT' | 'SAMPLE_COURSE' | 'FOUNDER_OUTREACH' | 'PARTNER' | 'REFERRAL'
  acquisitionCampaign?: string
  contactConsent: boolean
}

export const pilotLeadApi = {
  newKey: () => idempotencyKey('pilot-lead'),
  submit: (request: PilotLeadRequest, key: string) =>
    api<{ id: string; receivedAt: string }>('/api/v1/public/pilot-leads', {
      method: 'POST',
      headers: { 'Idempotency-Key': key },
      body: JSON.stringify(request),
    }),
}
