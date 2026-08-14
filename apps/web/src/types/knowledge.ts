export type KnowledgeDocumentStatus = 'DRAFT' | 'PUBLISHED'
export type KnowledgeDocumentType = 'FORENSIC_GUIDE' | 'POLICY' | 'MODEL_CARD' | 'OTHER'

export interface KnowledgeDocument {
  id: string; tenantId: string; title: string; documentType: KnowledgeDocumentType; content: string
  status: KnowledgeDocumentStatus; publishedVersion: number; createdBy: string; updatedBy: string
  version: number; createdAt: string; updatedAt: string; publishedAt: string | null
}

export interface KnowledgePublishResult {
  document: KnowledgeDocument; chunkCount: number; embeddingProvider: string
}

export interface KnowledgeReindexResult {
  embeddingProvider: string; dimensions: number; chunkCount: number
}

export interface KnowledgeSearchResult {
  documentId: string; documentTitle: string; documentType: string; documentVersion: number
  chunkId: string; chunkIndex: number; quote: string
  semanticScore: number; keywordScore: number; hybridScore: number
}

export interface RagDebugSearchResult {
  query: string; topK: number; embeddingProvider: string; results: KnowledgeSearchResult[]
}

export interface RagEvaluationCase {
  id: string; name: string; query: string; expectedDocumentId: string
  expectedChunkId: string | null; createdAt: string
}

export interface RagEvaluationCaseResult {
  evaluationCaseId: string; name: string; query: string; expectedDocumentId: string
  expectedChunkId: string | null; firstRelevantRank: number | null
  recalled: boolean; reciprocalRank: number; results: KnowledgeSearchResult[]
}

export interface RagEvaluationRun {
  id: string; topK: number; embeddingProvider: string; caseCount: number
  recallAtK: number; mrr: number; tenantIsolationPassed: boolean
  draftExclusionPassed: boolean; citationIntegrityPassed: boolean
  caseResults: RagEvaluationCaseResult[]; createdAt: string
}
