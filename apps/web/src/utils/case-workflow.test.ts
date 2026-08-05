import { describe, expect, it } from 'vitest'
import { nextInvestigatorTransition } from './case-workflow'

describe('nextInvestigatorTransition', () => {
  it('follows the investigator workflow through review submission', () => {
    expect(nextInvestigatorTransition('DRAFT', true, true)?.target).toBe('READY')
    expect(nextInvestigatorTransition('READY', true, true)?.target).toBe('INVESTIGATING')
    expect(nextInvestigatorTransition('INVESTIGATING', true, true)?.target).toBe('WAITING_REVIEW')
  })

  it('does not expose transitions without ownership or submit permission', () => {
    expect(nextInvestigatorTransition('DRAFT', false, true)).toBeNull()
    expect(nextInvestigatorTransition('INVESTIGATING', true, false)).toBeNull()
    expect(nextInvestigatorTransition('WAITING_REVIEW', true, true)).toBeNull()
  })
})
