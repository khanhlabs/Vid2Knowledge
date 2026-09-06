import { api, idempotencyKey } from '../../shared/api/client'

export interface Membership {
  id: string
  name: string
  slug: string
  role: 'OWNER' | 'ADMIN' | 'INSTRUCTOR' | 'REVIEWER' | 'LEARNER'
}

export interface Me {
  id: string
  email: string
  displayName: string
  organizations: Membership[]
}

export interface Usage {
  allowanceSeconds: number
  committedSeconds: number
  reservedSeconds: number
  availableSeconds: number
  actualAiCostMicrousd: number
  shadowAiCostMicrousd: number
  periodEnd: string
}

export interface Plan {
  id: string
  code: string
  name: string
  interval: string
  amountVnd: number
  processedVideoSeconds: number
}

export interface Subscription {
  id: string
  planCode: string
  planName: string
  status: 'ACTIVE' | 'PAST_DUE'
  periodStart: string
  periodEnd: string
  cancelAtPeriodEnd: boolean
}

export interface Invoice {
  id: string
  invoiceNumber: string
  state: 'OPEN' | 'PAID' | 'VOID' | 'REFUNDED'
  currency: 'VND'
  amountDueVnd: number
  amountPaidVnd: number
  dueAt: string
  paidAt?: string
  createdAt: string
}

export interface AnalysisJob {
  id: string
  sourceId: string
  state: string
  attempt: number
  packageId?: string
}

export interface LearningPackage {
  id: string
  sourceId: string
  state: string
  version: number
  revisionId: string
  revisionNo: number
  verificationState: string
  content: Record<string, unknown>
}

export interface Member {
  id: string
  email: string
  displayName: string
  role: Membership['role']
  status: string
  joinedAt: string
}

export interface Invitation {
  id: string
  email: string
  role: Membership['role']
  state: string
  expiresAt: string
  createdAt: string
}

export interface InvitationCreated {
  id: string
  token: string
  email: string
  role: Membership['role']
  expiresAt: string
}

export interface PackageSummary {
  id: string
  sourceId: string
  title: string
  state: string
  version: number
  revisionNo: number
  verificationState: string
}

export interface CourseSummary {
  id: string
  title: string
  description: string
  state: string
  version: number
  moduleCount: number
  lessonCount: number
}

export interface CohortSummary {
  id: string
  name: string
  status: string
  startsAt?: string
  endsAt?: string
  memberCount: number
}

export const workspaceApi = {
  me: () => api<Me>('/api/v1/me'),
  createOrganization: (name: string, slug: string) =>
    api<Membership>('/api/v1/organizations', {
      method: 'POST',
      body: JSON.stringify({ name, slug }),
    }),
  usage: (organizationId: string) =>
    api<Usage>(`/api/v1/organizations/${organizationId}/billing/usage`),
  plans: () => api<Plan[]>('/api/v1/billing/plans'),
  subscription: (organizationId: string) =>
    api<Subscription | null>(
      `/api/v1/organizations/${organizationId}/billing/subscription`,
    ),
  invoices: (organizationId: string) =>
    api<Invoice[]>(`/api/v1/organizations/${organizationId}/billing/invoices`),
  cancelSubscription: (organizationId: string, subscriptionId: string) =>
    api<void>(
      `/api/v1/organizations/${organizationId}/billing/subscriptions/${subscriptionId}/cancel`,
      { method: 'POST' },
    ),
  registerSource: (organizationId: string, youtubeUrl: string) =>
    api<{ id: string; title: string; durationSeconds: number }>(
      `/api/v1/organizations/${organizationId}/sources`,
      {
        method: 'POST',
        body: JSON.stringify({
          youtubeUrl,
          rightsBasis: 'OWNER',
          termsAccepted: true,
        }),
      },
    ),
  createAnalysis: (organizationId: string, sourceId: string) =>
    api<AnalysisJob>(`/api/v1/organizations/${organizationId}/analysis-jobs`, {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey('analysis') },
      body: JSON.stringify({
        sourceId,
        outputProfile: { language: 'auto', flashcards: 12, quizQuestions: 6 },
      }),
    }),
  job: (organizationId: string, jobId: string) =>
    api<AnalysisJob>(
      `/api/v1/organizations/${organizationId}/analysis-jobs/${jobId}`,
    ),
  learningPackage: (organizationId: string, packageId: string) =>
    api<LearningPackage>(
      `/api/v1/organizations/${organizationId}/packages/${packageId}`,
    ),
  transitionPackage: (
    organizationId: string,
    packageId: string,
    action: 'submit-review' | 'approve' | 'reject' | 'publish' | 'archive',
  ) =>
    api<LearningPackage>(
      `/api/v1/organizations/${organizationId}/packages/${packageId}/${action}`,
      { method: 'POST' },
    ),
  members: (organizationId: string) =>
    api<Member[]>(`/api/v1/organizations/${organizationId}/members`),
  invitations: (organizationId: string) =>
    api<Invitation[]>(`/api/v1/organizations/${organizationId}/invitations`),
  invite: (organizationId: string, email: string, role: string) =>
    api<InvitationCreated>(
      `/api/v1/organizations/${organizationId}/invitations`,
      {
        method: 'POST',
        body: JSON.stringify({ email, role }),
      },
    ),
  revokeInvitation: (organizationId: string, invitationId: string) =>
    api<void>(
      `/api/v1/organizations/${organizationId}/invitations/${invitationId}`,
      { method: 'DELETE' },
    ),
  packages: (organizationId: string) =>
    api<PackageSummary[]>(`/api/v1/organizations/${organizationId}/packages`),
  courses: (organizationId: string) =>
    api<CourseSummary[]>(`/api/v1/organizations/${organizationId}/courses`),
  cohorts: (organizationId: string) =>
    api<CohortSummary[]>(`/api/v1/organizations/${organizationId}/cohorts`),
  launchProgram: (
    organizationId: string,
    input: {
      title: string
      packageId: string
      learnerIds: string[]
      availableAt: string
      dueAt?: string
    },
  ) =>
    api<{ courseId: string; cohortId: string; assignmentId: string }>(
      `/api/v1/organizations/${organizationId}/program-launches`,
      {
        method: 'POST',
        headers: { 'Idempotency-Key': idempotencyKey('program-launch') },
        body: JSON.stringify(input),
      },
    ),
  checkout: (organizationId: string, planId: string) =>
    api<{ checkoutUrl: string }>(
      `/api/v1/organizations/${organizationId}/billing/checkout-sessions`,
      {
        method: 'POST',
        headers: { 'Idempotency-Key': idempotencyKey('checkout') },
        body: JSON.stringify({ planId }),
      },
    ),
}
