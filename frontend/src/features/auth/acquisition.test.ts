import { describe, expect, it } from 'vitest'
import { acquisitionSource, appDestination } from './acquisition'

describe('sample course acquisition', () => {
  it('keeps only the explicit allowlisted source through authentication', () => {
    expect(acquisitionSource('?source=sample-course&utm_email=private')).toBe(
      'SAMPLE_COURSE',
    )
    expect(appDestination('SAMPLE_COURSE')).toBe('/app?source=sample-course')
    expect(acquisitionSource('?source=untrusted')).toBeNull()
    expect(appDestination(null)).toBe('/app')
  })
})
