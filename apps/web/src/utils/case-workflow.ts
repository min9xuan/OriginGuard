import type { CaseStatus } from '../types/business'

export interface CaseTransitionAction {
  target: CaseStatus
  label: string
}

export function nextM11Transition(
  status: CaseStatus,
  canOperate: boolean,
  canSubmit: boolean,
): CaseTransitionAction | null {
  if (!canOperate) return null
  if (status === 'DRAFT') return { target: 'READY', label: '标记为 READY' }
  if (status === 'READY') return { target: 'INVESTIGATING', label: '开始调查' }
  if (status === 'INVESTIGATING' && canSubmit) {
    return { target: 'WAITING_REVIEW', label: '提交人工审核' }
  }
  return null
}
