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
variable "notification_from" {
  description = "Verified Resend sender, for example Vid2Knowledge <hello@example.com>."
  type        = string
  default     = ""
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
  }
}
