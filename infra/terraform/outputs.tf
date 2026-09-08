output "api_url" { value = google_cloud_run_v2_service.api.uri }
output "worker_url" { value = google_cloud_run_v2_service.worker.uri }
output "artifact_registry_repository" { value = google_artifact_registry_repository.backend.id }
output "runtime_service_account" { value = google_service_account.runtime.email }
output "task_invoker_service_account" { value = google_service_account.task_invoker.email }
output "sales_invoker_service_account" { value = google_service_account.sales_invoker.email }
output "sales_oidc_audience" { value = local.sales_audience }
output "operations_alert_policy_names" {
  value = {
    api_server_errors       = google_monitoring_alert_policy.api_server_errors.name
    worker_server_errors    = google_monitoring_alert_policy.worker_server_errors.name
    api_latency             = google_monitoring_alert_policy.api_latency.name
    analysis_queue_backlog  = google_monitoring_alert_policy.analysis_queue_backlog.name
    analysis_queue_failures = google_monitoring_alert_policy.analysis_queue_failures.name
    commercial_integrity    = google_monitoring_alert_policy.commercial_integrity_events.name
  }
}
