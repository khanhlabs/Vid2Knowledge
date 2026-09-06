export interface LearningPackage {
  video: { youtubeUrl: string; title: string; language: string }
  summary: {
    overview: string
    sections: Array<{ title: string; timestamp?: string; content: string[] }>
  }
  keyTakeaways: string[]
  flashcards: Array<{ question: string; answer: string; timestamp?: string }>
  quiz: Array<{
    question: string
    options: string[]
    correctAnswerIndex: number
    explanation: string
    timestamp?: string
  }>
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
