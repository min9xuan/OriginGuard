import type {
  KnowledgeDocument, KnowledgeDocumentType, KnowledgePublishResult, KnowledgeReindexResult, RagDebugSearchResult,
  RagEvaluationCase, RagEvaluationRun,
} from '../types/knowledge'
import { apiRequest } from './http'

export const knowledgeApi = {
  list: (accessToken: string) => apiRequest<KnowledgeDocument[]>('/knowledge-documents', {}, accessToken),
  create(title: string, documentType: KnowledgeDocumentType, content: string, accessToken: string) {
    return apiRequest<KnowledgeDocument>('/knowledge-documents', {
      method: 'POST', body: JSON.stringify({ title, documentType, content, version: 0 }),
    }, accessToken)
  },
  publish(documentId: string, version: number, accessToken: string) {
    return apiRequest<KnowledgePublishResult>(`/knowledge-documents/${documentId}/publish`, {
      method: 'POST', body: JSON.stringify({ version }),
    }, accessToken)
  },
  reindex(accessToken: string) {
    return apiRequest<KnowledgeReindexResult>('/knowledge-documents/reindex', {
      method: 'POST',
    }, accessToken)
  },
  debugSearch(query: string, topK: number, accessToken: string) {
    return apiRequest<RagDebugSearchResult>('/rag/debug-search', {
      method: 'POST', body: JSON.stringify({ query, topK }),
    }, accessToken)
  },
  listEvaluationCases(accessToken: string) {
    return apiRequest<RagEvaluationCase[]>('/rag/evaluation-cases', {}, accessToken)
  },
  createEvaluationCase(
    name: string, query: string, expectedDocumentId: string, expectedChunkId: string | null, accessToken: string,
  ) {
    return apiRequest<RagEvaluationCase>('/rag/evaluation-cases', {
      method: 'POST', body: JSON.stringify({ name, query, expectedDocumentId, expectedChunkId }),
    }, accessToken)
  },
  runEvaluation(topK: number, accessToken: string) {
    return apiRequest<RagEvaluationRun>('/rag/evaluation-runs', {
      method: 'POST', body: JSON.stringify({ topK }),
    }, accessToken)
  },
}
