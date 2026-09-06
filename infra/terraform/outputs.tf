output "api_url" { value = google_cloud_run_v2_service.api.uri }
output "worker_url" { value = google_cloud_run_v2_service.worker.uri }
output "artifact_registry_repository" { value = google_artifact_registry_repository.backend.id }
output "runtime_service_account" { value = google_service_account.runtime.email }
output "task_invoker_service_account" { value = google_service_account.task_invoker.email }
