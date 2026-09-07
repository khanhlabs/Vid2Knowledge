export type AcquisitionSource = 'SAMPLE_COURSE'

export function acquisitionSource(search: string): AcquisitionSource | null {
  return new URLSearchParams(search).get('source') === 'sample-course'
    ? 'SAMPLE_COURSE'
    : null
}

export function appDestination(source: AcquisitionSource | null): string {
  return source ? '/app?source=sample-course' : '/app'
}
