resource "google_secret_manager_secret" "runtime" {
  for_each  = toset(values(local.secret_env))
  secret_id = each.value
  labels    = local.labels
  replication {
    auto {}
  }
  depends_on = [google_project_service.required]
}

resource "google_secret_manager_secret_iam_member" "runtime_accessor" {
  for_each  = google_secret_manager_secret.runtime
  secret_id = each.value.id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.runtime.email}"
}
