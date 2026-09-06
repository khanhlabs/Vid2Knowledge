resource "google_secret_manager_secret" "runtime" {
  for_each  = toset(values(var.secret_ids))
  secret_id = each.value
  labels    = local.labels
  replication {
    auto {}
  }
  depends_on = [google_project_service.required]
}
