import { api, downloadApi, idempotencyKey } from '../../shared/api/client'

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
  qaQueryAllowance: number
  qaQueryCommitted: number
  qaQueryReserved: number
  availableQaQueries: number
  periodEnd: string
}

export interface Plan {
  id: string
  code: string
  name: string
  interval: string
  productType: 'SUBSCRIPTION' | 'TOP_UP'
  amountVnd: number
  processedVideoSeconds: number
  qaQueries: number
}

export interface KnowledgeIndexResult {
  packageRevisionId: string
  chunks: number
  embeddingModel: string
  rebuilt: boolean
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
  invoiceType: 'SUBSCRIPTION' | 'TOP_UP'
  amountDueVnd: number
  amountPaidVnd: number
  dueAt: string
  paidAt?: string
  createdAt: string
}

export interface Refund {
  id: string
  invoiceId: string
  amountVnd: number
  reason: string
  state: 'REQUESTED' | 'SUCCEEDED' | 'REJECTED'
  providerReference?: string
  requestedAt: string
  resolvedAt?: string
}

export interface DeletionRequest {
  id: string
  state: 'REQUESTED'
  requestedAt: string
  scheduledFor: string
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

export interface OrganizationOutcome {
  activeCohorts: number
  learners: number
  assigned: number
  started: number
  completed: number
  averageScorePercent?: number
  practiceAverageScorePercent?: number
  delayedRecallAverageScorePercent?: number
  feedbackResponses: number
  helpfulResponses: number
  reportedErrors: number
  openErrors: number
  activationRate: number
  completionRate: number
  timezone: string
  generatedAt: string
}

export interface CohortOutcome {
  id: string
  name: string
  status: string
  learners: number
  assigned: number
  started: number
  completed: number
  averageScorePercent?: number
  activationRate: number
  completionRate: number
}

export interface EconomicProfile {
  usdVndRate: number
  paymentFeeBps: number
  paymentFixedFeeVnd: number
  monthlyInfrastructureVnd: number
  monthlySupportMinutes: number
  supportHourlyVnd: number
  taxReserveBps: number
  acquisitionCostVnd: number
  monthlyLogoChurnBps: number
  assumptionsConfirmed: boolean
  updatedAt?: string
}

export interface ProfitabilityReport {
  from: string
  to: string
  assumptionsConfirmed: boolean
  status:
    | 'UNCONFIGURED'
    | 'NO_REVENUE'
    | 'NEGATIVE'
    | 'AI_COST_CRITICAL'
    | 'BELOW_FLOOR'
    | 'HEALTHY'
    | 'WATCH'
  grossCashVnd: number
  netCashVnd: number
  refundsVnd: number
  recognizedRevenueVnd: number
  actualAiCostVnd: number
  shadowAiCostVnd: number
  paymentFeesVnd: number
  allocatedInfrastructureVnd: number
  modeledSupportVnd: number
  manualDirectCostsVnd: number
  taxReserveVnd: number
  grossProfitVnd: number
  contributionProfitVnd: number
  grossMargin?: number
  contributionMargin?: number
  shadowAiRevenueShare?: number
  cacPaybackMonths?: number
  monthlyContributionVnd: number
  contributionLtvVnd?: number
  ltvCacRatio?: number
}

export interface ContentTemplate {
  id: string
  name: string
  outputProfile: {
    language: 'auto' | 'vi' | 'en'
    audience: 'student' | 'employee' | 'professional' | 'general'
    difficulty: 'beginner' | 'intermediate' | 'advanced'
    flashcards: number
    quizQuestions: number
    tone: 'concise' | 'supportive' | 'formal'
  }
  state: 'ACTIVE' | 'ARCHIVED'
  version: number
}

export interface ReviewQueueItem {
  packageId: string
  revisionId: string
  title: string
  revisionNo: number
  openFeedback: number
  submittedAt: string
}

export interface QuestionBankItem {
  id: string
  question: string
  difficulty: string
  validationState: string
  usageCount: number
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
  refunds: (organizationId: string) =>
    api<Refund[]>(`/api/v1/organizations/${organizationId}/billing/refunds`),
  requestRefund: (organizationId: string, invoiceId: string, reason: string) =>
    api<Refund>(`/api/v1/organizations/${organizationId}/billing/refunds`, {
      method: 'POST',
      body: JSON.stringify({ invoiceId, reason }),
    }),
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
  createAnalysis: (
    organizationId: string,
    sourceId: string,
    templateId?: string,
  ) =>
    api<AnalysisJob>(`/api/v1/organizations/${organizationId}/analysis-jobs`, {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey('analysis') },
      body: JSON.stringify(
        templateId
          ? { sourceId, templateId }
          : {
              sourceId,
              outputProfile: {
                language: 'auto',
                flashcards: 12,
                quizQuestions: 6,
              },
            },
      ),
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
    reason?: string,
  ) =>
    api<LearningPackage>(
      `/api/v1/organizations/${organizationId}/packages/${packageId}/${action}`,
      {
        method: 'POST',
        body: reason ? JSON.stringify({ reason }) : undefined,
      },
    ),
  indexKnowledge: (organizationId: string, packageId: string) =>
    api<KnowledgeIndexResult>(
      `/api/v1/organizations/${organizationId}/packages/${packageId}/knowledge-index`,
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
  outcomeOverview: (organizationId: string) =>
    api<OrganizationOutcome>(
      `/api/v1/organizations/${organizationId}/analytics/overview`,
    ),
  cohortOutcomes: (organizationId: string) =>
    api<CohortOutcome[]>(
      `/api/v1/organizations/${organizationId}/analytics/cohorts`,
    ),
  exportCohortOutcomes: (organizationId: string) =>
    downloadApi(
      `/api/v1/organizations/${organizationId}/analytics/cohorts.csv`,
      'ket-qua-cohort.csv',
    ),
  profitability: (organizationId: string) =>
    api<ProfitabilityReport>(
      `/api/v1/organizations/${organizationId}/analytics/profitability`,
    ),
  economicProfile: (organizationId: string) =>
    api<EconomicProfile>(
      `/api/v1/organizations/${organizationId}/analytics/profitability/assumptions`,
    ),
  updateEconomicProfile: (organizationId: string, profile: EconomicProfile) =>
    api<EconomicProfile>(
      `/api/v1/organizations/${organizationId}/analytics/profitability/assumptions`,
      { method: 'PUT', body: JSON.stringify(profile) },
    ),
  addDirectCost: (
    organizationId: string,
    input: {
      category: string
      amountVnd: number
      incurredAt: string
      note: string
    },
  ) =>
    api<{ id: string }>(
      `/api/v1/organizations/${organizationId}/analytics/profitability/costs`,
      { method: 'POST', body: JSON.stringify(input) },
    ),
  templates: (organizationId: string) =>
    api<ContentTemplate[]>(
      `/api/v1/organizations/${organizationId}/authoring/templates`,
    ),
  createTemplate: (
    organizationId: string,
    name: string,
    outputProfile: ContentTemplate['outputProfile'],
  ) =>
    api<ContentTemplate>(
      `/api/v1/organizations/${organizationId}/authoring/templates`,
      { method: 'POST', body: JSON.stringify({ name, outputProfile }) },
    ),
  reviewQueue: (organizationId: string) =>
    api<ReviewQueueItem[]>(
      `/api/v1/organizations/${organizationId}/authoring/review-queue`,
    ),
  questionBank: (organizationId: string) =>
    api<QuestionBankItem[]>(
      `/api/v1/organizations/${organizationId}/authoring/question-bank`,
    ),
  authoringSettings: (organizationId: string) =>
    api<{ approvalRequired: boolean }>(
      `/api/v1/organizations/${organizationId}/authoring/settings`,
    ),
  updateAuthoringSettings: (
    organizationId: string,
    approvalRequired: boolean,
  ) =>
    api<{ approvalRequired: boolean }>(
      `/api/v1/organizations/${organizationId}/authoring/settings`,
      { method: 'PATCH', body: JSON.stringify({ approvalRequired }) },
    ),
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
  exportPersonalData: () =>
    downloadApi('/api/v1/privacy/export', 'vid2knowledge-data.json'),
  activeDeletionRequest: () =>
    api<DeletionRequest | null>('/api/v1/privacy/deletion-request'),
  requestAccountDeletion: () =>
    api<DeletionRequest>('/api/v1/privacy/deletion-request', {
      method: 'POST',
    }),
  cancelAccountDeletion: () =>
    api<void>('/api/v1/privacy/deletion-request', { method: 'DELETE' }),
}
