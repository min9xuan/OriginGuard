import { describe, expect, it } from 'vitest'
import { nextM11Transition } from './case-workflow'

describe('nextM11Transition', () => {
  it('follows the M1.1 forward-only workflow', () => {
    expect(nextM11Transition('DRAFT', true, true)?.target).toBe('READY')
    expect(nextM11Transition('READY', true, true)?.target).toBe('INVESTIGATING')
    expect(nextM11Transition('INVESTIGATING', true, true)?.target).toBe('WAITING_REVIEW')
  })

  it('does not expose transitions without ownership or submit permission', () => {
    expect(nextM11Transition('DRAFT', false, true)).toBeNull()
    expect(nextM11Transition('INVESTIGATING', true, false)).toBeNull()
    expect(nextM11Transition('WAITING_REVIEW', true, true)).toBeNull()
  })
})
