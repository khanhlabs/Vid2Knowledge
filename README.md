# Vid2Knowledge

Vid2Knowledge turns supported public YouTube videos and authorised buyer-owned private videos into evidence-linked active-learning materials. The backend uses Gemini to generate structured notes, key takeaways, flashcards, and quizzes while enforcing tenant rights, quota, and cost controls.

The repository contains an actively implemented B2B2C MVP with a qualified paid-pilot funnel, tenant isolation, evidence-based buyer onboarding, authoring and learning workflows, billing, privacy controls, buyer-outcome analytics, and account-level profitability controls. Public production launch remains gated by real paid-pilot evidence, the Gemini benchmark, legal review, and configured cloud/provider credentials.

## Technology Stack

- React
- Vite
- Java
- Spring Boot
- Maven
- PostgreSQL
- Flyway
- Gemini API
- Docker
- GitHub Actions
- Terraform
- Cloudflare Pages/R2, Google Cloud Run/Tasks/Scheduler, and Supabase

## Product Direction

This list is updated as the product evolves.

- YouTube URL submission and validation
- Direct private MP4/WebM upload, rights attestation, and assignment-scoped playback
- AI-powered video analysis with Gemini
- Structured learning summaries
- Key takeaways
- Flashcards
- Multiple-choice quizzes with explanations
- Evidence links to original-video timestamps when available
- Analysis history, feedback, authentication, usage quota, and rate limiting
- Multi-tenant courses, cohorts, assignments, learner mastery and buyer outcome analytics
- Spaced repetition, exam mode, learning paths, and source-grounded course Q&A
- Usage-based subscriptions, expansion entitlements, integrations, and enterprise controls

## Project Structure

This section must be updated whenever a source directory is added, removed, or repurposed.

```text
Vid2Knowledge/
├── .github/
│   └── workflows/
│       └── ci.yml                         # GitHub Actions continuous-integration workflow
├── backend/
│   ├── .mvn/
│   │   └── wrapper/                       # Maven Wrapper configuration
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/vid2knowledge/
│   │   │   │   ├── Vid2KnowledgeApplication.java # Spring Boot application entry point
│   │   │   │   ├── analysis/              # Video-analysis bounded context
│   │   │   │   │   ├── api/               # REST endpoints and request/response DTOs
│   │   │   │   │   ├── application/       # Use cases and application services
│   │   │   │   │   ├── domain/            # Analysis domain model and business rules
│   │   │   │   │   └── infrastructure/    # Gemini client, persistence, and external adapters
│   │   │   │   ├── auth/                  # Authentication and authorization module
│   │   │   │   ├── common/                # Cross-cutting backend code
│   │   │   │   │   ├── api/               # Shared API contracts
│   │   │   │   │   ├── exception/         # API error model and global exception handling
│   │   │   │   │   └── validation/        # Reusable validation rules
│   │   │   │   ├── config/                # Security, CORS, and configuration properties
│   │   │   │   ├── sales/                 # Public pilot capture and restricted sales funnel
│   │   │   │   ├── usage/                 # Quota and usage-tracking module
│   │   │   │   └── user/                  # User-profile module
│   │   │   └── resources/
│   │   │       ├── application.yaml       # Shared Spring Boot configuration
│   │   │       ├── application-local.yaml # Local environment configuration
│   │   │       ├── application-prod.yaml  # Production environment configuration
│   │   │       ├── db/migration/          # Flyway SQL migrations
│   │   │       ├── static/                # Static backend-served assets, if needed
│   │   │       └── templates/             # Server-side templates, if needed
│   │   └── test/
│   │       └── java/com/vid2knowledge/    # Backend integration and unit tests
│   ├── pom.xml                            # Maven dependencies and build configuration
│   ├── mvnw                               # Maven Wrapper for Unix-like systems
│   └── mvnw.cmd                           # Maven Wrapper for Windows
├── frontend/
│   ├── e2e/                               # Desktop/mobile browser smoke tests
│   ├── playwright.config.ts               # Isolated production-build smoke runner
│   ├── playwright.full.config.ts          # Authenticated journey against a test backend
│   ├── src/
│   │   ├── app/                           # Application shell and root React component
│   │   ├── assets/
│   │   │   └── logo/                      # Brand assets
│   │   ├── features/                      # Feature-based UI modules
│   │   │   ├── analysis/                  # Public analysis flow
│   │   │   ├── auth/                      # Authentication UI
│   │   │   ├── history/                   # Analysis-history UI
│   │   │   ├── learner/                   # Assignment and learning experience
│   │   │   ├── sales/                     # Qualified paid-pilot landing and capture
│   │   │   ├── usage/                     # Quota and usage UI
│   │   │   └── workspace/                 # Buyer operations, billing, authoring and analytics
│   │   ├── shared/                        # Reusable frontend code
│   │   │   ├── api/                       # Shared HTTP client and API utilities
│   │   │   ├── components/                # Shared UI components
│   │   │   ├── hooks/                     # Reusable React hooks
│   │   │   ├── lib/                       # Framework-agnostic utilities
│   │   │   └── styles/                    # Shared style definitions
│   │   ├── test/                          # Frontend test setup
│   │   ├── main.tsx                       # React bootstrap entry point
│   │   └── style.css                      # Global styles
│   ├── index.html                         # Vite HTML entry point
│   ├── package.json                       # Frontend scripts and dependencies
│   └── package-lock.json                  # Locked npm dependency versions
├── docs/
│   ├── features.md                        # Product strategy, scope, and monetisation hypotheses
│   ├── Plan.md                            # Gated implementation and investment plan
│   ├── Business-Model.md                  # Buyer, go-to-market, unit economics, and profit gates
│   ├── Technical-Architecture.md           # Target stack, boundaries, security, deployment, and cost controls
│   ├── Data-and-API.md                     # Data model, state machines, APIs, events, and test matrix
│   └── feasibility-result.md              # Gemini benchmark protocol and decision record
├── infra/
│   ├── cloud-run/                         # Cloud Run deployment resources
│   ├── docker/                            # Docker-related resources
│   └── terraform/                         # Infrastructure as Code resources
├── ops/
│   ├── backup-database.ps1                # Encrypted logical backup and optional R2 upload
│   ├── restore-drill.ps1                  # Empty-target restore verification and evidence
│   └── sales.ps1                          # Guarded founder sales-queue operations
├── docker-compose.yaml                    # Local PostgreSQL container
└── README.md                              # Project documentation
```

## Access

Browser smoke checks run with `./dev.ps1 test-e2e`, or `npm run test:e2e` from `frontend`.
Install the browser once with `npx playwright install chromium` after `npm ci`.
The runner builds an isolated `dist-smoke` with cloud auth disabled, starts its own preview server on port 4173, and checks sample learning,
pilot form consent/retry/attribution, and signed-out route protection on desktop and mobile Chromium.
Pilot HTTP responses are mocked; these checks do not prove live Supabase, payOS, or AI integration.
Use `npm run build` for deployable assets; `dist-smoke` is only for these tests.
CI runs the same suite and keeps failure traces/screenshots for seven days, following
the [Playwright CI workflow](https://playwright.dev/docs/ci).

For the authenticated flow, start Docker and run `./dev.ps1 test-e2e-full`. On other platforms, run
`./mvnw -Pbrowser-e2e test-compile failsafe:integration-test failsafe:verify` from `backend`.
This starts PostgreSQL Testcontainers, a loopback JWKS server with ephemeral RSA keys, the real backend
with JWT authentication enabled, and a separate frontend build in `dist-journey`. The journey creates
an organization, generates/reviews/publishes learning material, invites a learner, launches a cohort,
submits an assessment, and checks tenant/role denials. Database assertions reconcile grading and usage/cost.
Only video metadata and Gemini responses are replaced by test fixtures; no production test-login endpoint
or bypass is shipped. This does not validate provider quality, email delivery, or hosted Supabase login.
The journey also switches an already-open owner's page to the learner identity without reloading,
verifying that cached owner data and staff actions disappear. Frontend unit tests cover delayed
responses, private downloads, upload cancellation and offline mutations across identity changes.
The opt-in Maven profile uses [Failsafe integration-test and verify](https://maven.apache.org/surefire/maven-failsafe-plugin/index.html).

There is no public deployment URL yet. This section will be updated when the application is deployed.

| Environment | Frontend | Backend API |
|---|---|---|
| Local development | `http://localhost:5173` | `http://localhost:8080` |
| Public deployment | Coming soon | Coming soon |

## Documentation

- [Product Strategy and Target Scope](docs/features.md)
- [Business Model and Profitability Gates](docs/Business-Model.md)
- [Detailed Implementation Plan](docs/Plan.md)
- [Technical Architecture](docs/Technical-Architecture.md)
- [Data Model and API Contracts](docs/Data-and-API.md)
- [Production Operations and Launch Gate](docs/Operations.md)
- [Gemini Feasibility Benchmark](docs/feasibility-result.md)

## Author

**Pham Gia Khanh**

Full-Stack Developer

**Core Technologies**

- React
- HTML
- CSS
- JavaScript
- Java
- Spring
- Docker
- CI/CD
- GitHub Actions

**AWS Cloud and Infrastructure as Code**

- IAM
- Route 53
- Security Groups
- EC2
- S3
- Terraform
