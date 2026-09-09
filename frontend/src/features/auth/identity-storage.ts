const identityKey = 'v2k.storageIdentity'

export function syncStoredIdentity(userId: string | null) {
  if (userId === null || localStorage.getItem(identityKey) !== userId) {
    localStorage.removeItem('v2k.organizationId')
  }
  if (userId) localStorage.setItem(identityKey, userId)
  else localStorage.removeItem(identityKey)
}

export function packageDraftKey(
  userId: string,
  organizationId: string,
  packageId: string,
) {
  return `v2k.package-draft.${userId}.${organizationId}.${packageId}`
}
