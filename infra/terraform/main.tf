locals {
  prefix = "v2k-${var.environment}"
  labels = {
    app         = "vid2knowledge"
    environment = var.environment
    cost_center = "product"
  }
  internal_audience = "https://tasks.vid2knowledge.internal/${var.environment}"
  core_secret_env = {
    DB_URL          = var.secret_ids.db_url
    DB_USERNAME     = var.secret_ids.db_username
    DB_PASSWORD     = var.secret_ids.db_password
    GEMINI_API_KEY  = var.secret_ids.gemini_api_key
    YOUTUBE_API_KEY = var.secret_ids.youtube_api_key
  }
  payos_secret_env = {
    PAYOS_CLIENT_ID    = var.secret_ids.payos_client_id
    PAYOS_API_KEY      = var.secret_ids.payos_api_key
    PAYOS_CHECKSUM_KEY = var.secret_ids.payos_checksum_key
  }
  notification_secret_env = {
    RESEND_API_KEY              = var.secret_ids.resend_api_key
    NOTIFICATION_ENCRYPTION_KEY = var.secret_ids.notification_encryption_key
  }
  secret_env = merge(
    local.core_secret_env,
    var.payos_enabled ? local.payos_secret_env : {},
    var.notifications_enabled ? local.notification_secret_env : {}
  )
  worker_plain_env = {
    SPRING_PROFILES_ACTIVE             = "prod"
    TASK_QUEUE_MODE                    = "INLINE"
    OUTBOX_POLLER_ENABLED              = "false"
    AUTH_ISSUER_URI                    = var.auth_issuer_uri
    AUTH_AUDIENCE                      = var.auth_audience
    TASK_SERVICE_ACCOUNT_EMAIL         = google_service_account.task_invoker.email
    TASK_OIDC_AUDIENCE                 = local.internal_audience
    PAYOS_ENABLED                      = tostring(var.payos_enabled)
    PAYOS_RETURN_URL                   = var.payos_return_url
    PAYOS_CANCEL_URL                   = var.payos_cancel_url
    NOTIFICATIONS_ENABLED              = tostring(var.notifications_enabled)
    NOTIFICATION_FROM                  = var.notification_from
    FRONTEND_BASE_URL                  = var.frontend_origin
    LEGAL_POLICY_SET_VERSION           = var.legal_policies.policy_set_version
    LEGAL_TERMS_VERSION                = var.legal_policies.terms_version
    LEGAL_TERMS_URL                    = var.legal_policies.terms_url
    LEGAL_PRIVACY_VERSION              = var.legal_policies.privacy_version
    LEGAL_PRIVACY_URL                  = var.legal_policies.privacy_url
    LEGAL_ACCEPTABLE_USE_VERSION       = var.legal_policies.acceptable_use_version
    LEGAL_ACCEPTABLE_USE_URL           = var.legal_policies.acceptable_use_url
    LEGAL_AI_NOTICE_VERSION            = var.legal_policies.ai_notice_version
    LEGAL_AI_NOTICE_URL                = var.legal_policies.ai_notice_url
    LEGAL_REVIEWED                     = tostring(var.legal_policies.reviewed)
    LEGAL_REQUIRE_PRODUCTION_READINESS = tostring(var.environment == "prod")
    DB_POOL_MAX_SIZE                   = "4"
  }
  api_plain_env = merge(local.worker_plain_env, {
    TASK_QUEUE_MODE      = "CLOUD_TASKS"
    GCP_PROJECT          = var.project_id
    GCP_TASKS_LOCATION   = var.region
    GCP_ANALYSIS_QUEUE   = google_cloud_tasks_queue.analysis.name
    WORKER_BASE_URL      = google_cloud_run_v2_service.worker.uri
    CORS_ALLOWED_ORIGINS = var.frontend_origin
  })
}

data "google_project" "current" {}

resource "google_project_service" "required" {
  for_each = toset([
    "artifactregistry.googleapis.com",
    "billingbudgets.googleapis.com",
    "cloudtasks.googleapis.com",
    "cloudscheduler.googleapis.com",
    "iamcredentials.googleapis.com",
    "run.googleapis.com",
    "secretmanager.googleapis.com",
  ])
  service            = each.value
  disable_on_destroy = false
}

resource "google_artifact_registry_repository" "backend" {
  location      = var.region
  repository_id = "vid2knowledge"
  format        = "DOCKER"
  labels        = local.labels
  depends_on    = [google_project_service.required]
}

resource "google_service_account" "runtime" {
  account_id   = "${local.prefix}-runtime"
  display_name = "Vid2Knowledge ${var.environment} runtime"
}

resource "google_service_account" "task_invoker" {
  account_id   = "${local.prefix}-tasks"
  display_name = "Vid2Knowledge signed task invoker"
}

resource "google_project_iam_member" "runtime_task_enqueuer" {
  project = var.project_id
  role    = "roles/cloudtasks.enqueuer"
  member  = "serviceAccount:${google_service_account.runtime.email}"
}

resource "google_project_iam_member" "runtime_secret_accessor" {
  project = var.project_id
  role    = "roles/secretmanager.secretAccessor"
  member  = "serviceAccount:${google_service_account.runtime.email}"
}

resource "google_service_account_iam_member" "tasks_token_creator" {
  service_account_id = google_service_account.task_invoker.name
  role               = "roles/iam.serviceAccountTokenCreator"
  member             = "serviceAccount:service-${data.google_project.current.number}@gcp-sa-cloudtasks.iam.gserviceaccount.com"
}

resource "google_service_account_iam_member" "scheduler_token_creator" {
  service_account_id = google_service_account.task_invoker.name
  role               = "roles/iam.serviceAccountTokenCreator"
  member             = "serviceAccount:service-${data.google_project.current.number}@gcp-sa-cloudscheduler.iam.gserviceaccount.com"
}

resource "google_cloud_tasks_queue" "analysis" {
  name     = "${local.prefix}-analysis"
  location = var.region

  rate_limits {
    max_concurrent_dispatches = 3
    max_dispatches_per_second = 2
  }
  retry_config {
    max_attempts       = 5
    max_retry_duration = "3600s"
    min_backoff        = "30s"
    max_backoff        = "600s"
    max_doublings      = 4
  }
  depends_on = [google_project_service.required]
}

resource "google_cloud_run_v2_service" "worker" {
  name                = "${local.prefix}-worker"
  location            = var.region
  deletion_protection = var.environment == "prod"
  ingress             = "INGRESS_TRAFFIC_ALL"
  labels              = local.labels
  custom_audiences    = [local.internal_audience]

  template {
    service_account                  = google_service_account.runtime.email
    timeout                          = "300s"
    max_instance_request_concurrency = 4
    scaling {
      min_instance_count = 0
      max_instance_count = 3
    }
    containers {
      image = var.backend_image
      resources {
        cpu_idle = true
        limits   = { cpu = "1", memory = "1Gi" }
      }
      ports { container_port = 8080 }
      startup_probe {
        initial_delay_seconds = 5
        timeout_seconds       = 2
        period_seconds        = 5
        failure_threshold     = 24
        http_get { path = "/actuator/health/liveness" }
      }
      dynamic "env" {
        for_each = local.secret_env
        content {
          name = env.key
          value_source {
            secret_key_ref {
              secret  = env.value
              version = "latest"
            }
          }
        }
      }
      dynamic "env" {
        for_each = local.worker_plain_env
        content {
          name  = env.key
          value = env.value
        }
      }
    }
  }
  depends_on = [google_project_service.required, google_secret_manager_secret.runtime]
}

resource "google_cloud_run_v2_service" "api" {
  name                = "${local.prefix}-api"
  location            = var.region
  deletion_protection = var.environment == "prod"
  ingress             = "INGRESS_TRAFFIC_ALL"
  labels              = local.labels
  custom_audiences    = [local.internal_audience]

  template {
    service_account                  = google_service_account.runtime.email
    timeout                          = "60s"
    max_instance_request_concurrency = 40
    scaling {
      min_instance_count = 0
      max_instance_count = 3
    }
    containers {
      image = var.backend_image
      resources {
        cpu_idle = true
        limits   = { cpu = "1", memory = "768Mi" }
      }
      ports { container_port = 8080 }
      startup_probe {
        initial_delay_seconds = 5
        timeout_seconds       = 2
        period_seconds        = 5
        failure_threshold     = 24
        http_get { path = "/actuator/health/liveness" }
      }
      dynamic "env" {
        for_each = local.secret_env
        content {
          name = env.key
          value_source {
            secret_key_ref {
              secret  = env.value
              version = "latest"
            }
          }
        }
      }
      dynamic "env" {
        for_each = local.api_plain_env
        content {
          name  = env.key
          value = env.value
        }
      }
    }
  }
  depends_on = [google_project_service.required, google_secret_manager_secret.runtime]
}

resource "google_billing_budget" "project" {
  count           = var.billing_account_id == "" ? 0 : 1
  billing_account = var.billing_account_id
  display_name    = "${local.prefix}-monthly-guardrail"

  budget_filter {
    projects = ["projects/${data.google_project.current.number}"]
  }
  amount {
    specified_amount {
      currency_code = "USD"
      units         = tostring(var.monthly_budget_usd)
    }
  }
  threshold_rules { threshold_percent = 0.5 }
  threshold_rules { threshold_percent = 0.8 }
  threshold_rules { threshold_percent = 1.0 }

  depends_on = [google_project_service.required]
}

resource "google_cloud_run_v2_service_iam_member" "public_api" {
  project  = var.project_id
  location = google_cloud_run_v2_service.api.location
  name     = google_cloud_run_v2_service.api.name
  role     = "roles/run.invoker"
  member   = "allUsers"
}

resource "google_cloud_run_v2_service_iam_member" "private_worker" {
  project  = var.project_id
  location = google_cloud_run_v2_service.worker.location
  name     = google_cloud_run_v2_service.worker.name
  role     = "roles/run.invoker"
  member   = "serviceAccount:${google_service_account.task_invoker.email}"
}

resource "google_cloud_scheduler_job" "outbox" {
  name             = "${local.prefix}-outbox"
  region           = var.region
  schedule         = "* * * * *"
  time_zone        = "Etc/UTC"
  attempt_deadline = "60s"
  retry_config {
    retry_count          = 2
    min_backoff_duration = "10s"
    max_backoff_duration = "30s"
  }
  http_target {
    http_method = "POST"
    uri         = "${google_cloud_run_v2_service.api.uri}/internal/tasks/outbox"
    headers     = { "Content-Type" = "application/json" }
    oidc_token {
      service_account_email = google_service_account.task_invoker.email
      audience              = local.internal_audience
    }
  }
  depends_on = [google_cloud_run_v2_service_iam_member.public_api]
}

resource "google_cloud_scheduler_job" "billing_reconciliation" {
  name             = "${local.prefix}-billing-reconciliation"
  region           = var.region
  schedule         = "*/10 * * * *"
  time_zone        = "Etc/UTC"
  attempt_deadline = "60s"
  retry_config {
    retry_count          = 2
    min_backoff_duration = "30s"
    max_backoff_duration = "120s"
  }
  http_target {
    http_method = "POST"
    uri         = "${google_cloud_run_v2_service.api.uri}/internal/tasks/billing/reconcile"
    headers     = { "Content-Type" = "application/json" }
    oidc_token {
      service_account_email = google_service_account.task_invoker.email
      audience              = local.internal_audience
    }
  }
  depends_on = [google_cloud_run_v2_service_iam_member.public_api]
}

resource "google_cloud_scheduler_job" "notification_dispatch" {
  count            = var.notifications_enabled ? 1 : 0
  name             = "${local.prefix}-notification-dispatch"
  region           = var.region
  schedule         = "* * * * *"
  time_zone        = "Etc/UTC"
  attempt_deadline = "60s"
  retry_config {
    retry_count          = 2
    min_backoff_duration = "10s"
    max_backoff_duration = "30s"
  }
  http_target {
    http_method = "POST"
    uri         = "${google_cloud_run_v2_service.api.uri}/internal/tasks/notifications/dispatch"
    headers     = { "Content-Type" = "application/json" }
    oidc_token {
      service_account_email = google_service_account.task_invoker.email
      audience              = local.internal_audience
    }
  }
  depends_on = [google_cloud_run_v2_service_iam_member.public_api]
}
