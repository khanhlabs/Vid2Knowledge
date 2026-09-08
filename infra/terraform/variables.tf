variable "project_id" {
  description = "GCP project dedicated to Vid2Knowledge."
  type        = string
}

variable "region" {
  description = "Singapore keeps latency low for the initial Vietnam market."
  type        = string
  default     = "asia-southeast1"
}

variable "environment" {
  type    = string
  default = "prod"
  validation {
    condition     = contains(["staging", "prod"], var.environment)
    error_message = "environment must be staging or prod"
  }
}

variable "backend_image" {
  description = "Immutable Artifact Registry image reference, preferably pinned by digest."
  type        = string
}

variable "auth_issuer_uri" { type = string }
variable "auth_audience" {
  type    = string
  default = "authenticated"
}
variable "frontend_origin" { type = string }
variable "sales_operator_members" {
  description = "IAM principals allowed to impersonate the PII-restricted sales service account, for example user:founder@example.com."
  type        = set(string)
  default     = []
  validation {
    condition = alltrue([
      for member in var.sales_operator_members : can(regex("^(user|group):[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", member))
    ])
    error_message = "sales_operator_members must contain user: or group: IAM principals with valid email addresses."
  }
}
variable "billing_account_id" {
  description = "Optional Cloud Billing account ID. Set it to create hard-to-ignore project budget alerts."
  type        = string
  default     = ""
  sensitive   = true
}
variable "monthly_budget_usd" {
  description = "Monthly GCP budget for this environment; alerts fire at 50%, 80%, and 100%."
  type        = number
  default     = 25
  validation {
    condition     = var.monthly_budget_usd >= 1
    error_message = "monthly_budget_usd must be at least 1"
  }
}

variable "alert_notification_emails" {
  description = "Operational alert recipients. Production requires at least two independent addresses."
  type        = set(string)
  default     = []
  validation {
    condition = alltrue([
      for email in var.alert_notification_emails : can(regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", email))
    ])
    error_message = "Every alert_notification_emails entry must be an email address."
  }
}
variable "payos_enabled" {
  type    = bool
  default = false
}
variable "payos_return_url" { type = string }
variable "payos_cancel_url" { type = string }
variable "notifications_enabled" {
  type    = bool
  default = false
}
variable "integrations_enabled" {
  description = "Enable paid Business API keys and outbound webhooks after the encryption secret exists."
  type        = bool
  default     = false
}
variable "object_storage_enabled" {
  description = "Enable direct private-video uploads through a Cloudflare R2 S3-compatible bucket."
  type        = bool
  default     = false
}
variable "object_storage_endpoint" {
  description = "Account-specific R2 S3 API endpoint; required and HTTPS when object storage is enabled."
  type        = string
  default     = ""
  validation {
    condition     = var.object_storage_endpoint == "" || can(regex("^https://[^/]+/?$", var.object_storage_endpoint))
    error_message = "object_storage_endpoint must be an HTTPS origin without a path"
  }
}
variable "object_storage_bucket" {
  description = "Private R2 bucket name."
  type        = string
  default     = ""
}
variable "notification_from" {
  description = "Verified Resend sender, for example Vid2Knowledge <hello@example.com>."
  type        = string
  default     = ""
}
variable "sales_alert_recipient" {
  description = "Founder inbox receiving PII-minimized paid-pilot lead alerts when notifications are enabled."
  type        = string
  default     = ""
  validation {
    condition     = var.sales_alert_recipient == "" || can(regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", var.sales_alert_recipient))
    error_message = "sales_alert_recipient must be an email address."
  }
}

variable "legal_policies" {
  description = "Versioned public legal documents. Production must use counsel-reviewed HTTPS URLs."
  type = object({
    policy_set_version     = string
    terms_version          = string
    terms_url              = string
    privacy_version        = string
    privacy_url            = string
    acceptable_use_version = string
    acceptable_use_url     = string
    ai_notice_version      = string
    ai_notice_url          = string
    reviewed               = bool
  })
  default = {
    policy_set_version     = "2026-09-draft"
    terms_version          = "2026-09-draft"
    terms_url              = "/legal/terms"
    privacy_version        = "2026-09-draft"
    privacy_url            = "/legal/privacy"
    acceptable_use_version = "2026-09-draft"
    acceptable_use_url     = "/legal/acceptable-use"
    ai_notice_version      = "2026-09-draft"
    ai_notice_url          = "/legal/ai-notice"
    reviewed               = false
  }
}

variable "secret_ids" {
  description = "Secret Manager IDs. Add secret versions out-of-band so values never enter Terraform state."
  type = object({
    db_url                      = string
    db_username                 = string
    db_password                 = string
    gemini_api_key              = string
    youtube_api_key             = string
    payos_client_id             = string
    payos_api_key               = string
    payos_checksum_key          = string
    resend_api_key              = string
    notification_encryption_key = string
    integration_encryption_key  = string
    r2_access_key_id            = string
    r2_secret_access_key        = string
  })
  default = {
    db_url                      = "v2k-db-url"
    db_username                 = "v2k-db-username"
    db_password                 = "v2k-db-password"
    gemini_api_key              = "v2k-gemini-api-key"
    youtube_api_key             = "v2k-youtube-api-key"
    payos_client_id             = "v2k-payos-client-id"
    payos_api_key               = "v2k-payos-api-key"
    payos_checksum_key          = "v2k-payos-checksum-key"
    resend_api_key              = "v2k-resend-api-key"
    notification_encryption_key = "v2k-notification-encryption-key"
    integration_encryption_key  = "v2k-integration-encryption-key"
    r2_access_key_id            = "v2k-r2-access-key-id"
    r2_secret_access_key        = "v2k-r2-secret-access-key"
  }
}
