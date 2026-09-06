export interface LearningPackage {
  schemaVersion: string
  video: {
    youtubeUrl: string
    videoId: string
    title: string
    language: string
  }
  summary: {
    overview: string
    sections: Array<{
      id: string
      title: string
      source: SourceReference
      content: string[]
    }>
  }
  keyTakeaways: Array<{
    id: string
    text: string
    source: SourceReference
  }>
  flashcards: Array<{
    id: string
    question: string
    answer: string
    source: SourceReference
  }>
  quiz: Array<{
    id: string
    question: string
    options: string[]
    correctAnswerIndex: number
    explanation: string
    source: SourceReference
  }>
}

interface SourceReference {
  timestampSeconds: number
  evidence: string
}

interface ApiError {
  message?: string
  detail?: string
  correlationId?: string
}

export async function createPreview(
  youtubeUrl: string,
): Promise<LearningPackage> {
  const response = await fetch('/api/v1/analysis/preview', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ youtubeUrl }),
  })

  if (!response.ok) {
    const error = (await response.json().catch(() => ({}))) as ApiError
    const reference = error.correlationId
      ? ` (mã hỗ trợ: ${error.correlationId})`
      : ''
    throw new Error(
      `${error.detail ?? error.message ?? 'Không thể xử lý video.'}${reference}`,
    )
  }

  return (await response.json()) as LearningPackage
}
