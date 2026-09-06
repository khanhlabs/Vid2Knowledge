import type { LearningPackageContent, SourceReference } from './api'

interface Props {
  value: LearningPackageContent
  disabled?: boolean
  onChange: (value: LearningPackageContent) => void
}

const clone = <T,>(value: T): T => structuredClone(value)
const newId = (prefix: string) => `${prefix}-${crypto.randomUUID()}`

function move<T>(items: T[], index: number, offset: number) {
  const target = index + offset
  if (target < 0 || target >= items.length) return items
  const result = [...items]
  const current = result[index]!
  result[index] = result[target]!
  result[target] = current
  return result
}

function RowActions({
  index,
  length,
  canRemove,
  canDuplicate,
  onMove,
  onDuplicate,
  onRemove,
}: {
  index: number
  length: number
  canRemove: boolean
  canDuplicate: boolean
  onMove: (offset: number) => void
  onDuplicate: () => void
  onRemove: () => void
}) {
  return (
    <div className="editor-row-actions">
      <button type="button" disabled={index === 0} onClick={() => onMove(-1)}>
        ↑
      </button>
      <button
        type="button"
        disabled={index === length - 1}
        onClick={() => onMove(1)}
      >
        ↓
      </button>
      <button type="button" disabled={!canDuplicate} onClick={onDuplicate}>
        Nhân bản
      </button>
      <button type="button" disabled={!canRemove} onClick={onRemove}>
        Xóa
      </button>
    </div>
  )
}

function SourceFields({
  value,
  onChange,
}: {
  value: SourceReference
  onChange: (value: SourceReference) => void
}) {
  return (
    <div className="source-fields">
      <label>
        Timestamp (giây)
        <input
          type="number"
          min="0"
          required
          value={value.timestampSeconds}
          onChange={(event) =>
            onChange({ ...value, timestampSeconds: Number(event.target.value) })
          }
        />
      </label>
      <label>
        Bằng chứng nguồn
        <input
          required
          maxLength={500}
          value={value.evidence}
          onChange={(event) =>
            onChange({ ...value, evidence: event.target.value })
          }
        />
      </label>
    </div>
  )
}

export function PackageEditor({ value, disabled, onChange }: Props) {
  const change = (recipe: (draft: LearningPackageContent) => void) => {
    const draft = clone(value)
    recipe(draft)
    onChange(draft)
  }

  return (
    <fieldset className="package-editor" disabled={disabled}>
      <legend>Structured learning package</legend>
      <label>
        Tiêu đề học liệu
        <input
          required
          maxLength={300}
          value={value.video.title}
          onChange={(event) =>
            change((draft) => {
              draft.video.title = event.target.value
            })
          }
        />
      </label>
      <label>
        Ngôn ngữ
        <input
          required
          maxLength={16}
          value={value.video.language}
          onChange={(event) =>
            change((draft) => {
              draft.video.language = event.target.value
            })
          }
        />
      </label>
      <label>
        Tổng quan
        <textarea
          required
          maxLength={5000}
          rows={6}
          value={value.summary.overview}
          onChange={(event) =>
            change((draft) => {
              draft.summary.overview = event.target.value
            })
          }
        />
      </label>

      <h2>Các phần nội dung</h2>
      {value.summary.sections.map((section, index) => (
        <article className="editor-card" key={section.id}>
          <h3>Phần {index + 1}</h3>
          <label>
            Tiêu đề phần
            <input
              required
              maxLength={300}
              value={section.title}
              onChange={(event) =>
                change((draft) => {
                  draft.summary.sections[index]!.title = event.target.value
                })
              }
            />
          </label>
          <label>
            Nội dung (mỗi đoạn một dòng)
            <textarea
              required
              rows={5}
              value={section.content.join('\n')}
              onChange={(event) =>
                change((draft) => {
                  draft.summary.sections[index]!.content = event.target.value
                    .split('\n')
                    .filter((line) => line.trim().length > 0)
                })
              }
            />
          </label>
          <SourceFields
            value={section.source}
            onChange={(source) =>
              change((draft) => {
                draft.summary.sections[index]!.source = source
              })
            }
          />
          <RowActions
            index={index}
            length={value.summary.sections.length}
            canRemove={value.summary.sections.length > 1}
            canDuplicate={value.summary.sections.length < 30}
            onMove={(offset) =>
              change((draft) => {
                draft.summary.sections = move(
                  draft.summary.sections,
                  index,
                  offset,
                )
              })
            }
            onDuplicate={() =>
              change((draft) => {
                const copy = clone(draft.summary.sections[index]!)
                copy.id = newId('section')
                draft.summary.sections.splice(index + 1, 0, copy)
              })
            }
            onRemove={() =>
              change((draft) => {
                draft.summary.sections.splice(index, 1)
              })
            }
          />
        </article>
      ))}

      <h2>Key takeaways</h2>
      {value.keyTakeaways.map((item, index) => (
        <article className="editor-card" key={item.id}>
          <label>
            Ý chính {index + 1}
            <textarea
              required
              maxLength={1000}
              value={item.text}
              onChange={(event) =>
                change((draft) => {
                  draft.keyTakeaways[index]!.text = event.target.value
                })
              }
            />
          </label>
          <SourceFields
            value={item.source}
            onChange={(source) =>
              change((draft) => {
                draft.keyTakeaways[index]!.source = source
              })
            }
          />
        </article>
      ))}

      <h2>Flashcards</h2>
      {value.flashcards.map((card, index) => (
        <article className="editor-card" key={card.id}>
          <h3>Thẻ {index + 1}</h3>
          <label>
            Câu hỏi
            <textarea
              required
              maxLength={1000}
              value={card.question}
              onChange={(event) =>
                change((draft) => {
                  draft.flashcards[index]!.question = event.target.value
                })
              }
            />
          </label>
          <label>
            Câu trả lời
            <textarea
              required
              maxLength={2000}
              value={card.answer}
              onChange={(event) =>
                change((draft) => {
                  draft.flashcards[index]!.answer = event.target.value
                })
              }
            />
          </label>
          <SourceFields
            value={card.source}
            onChange={(source) =>
              change((draft) => {
                draft.flashcards[index]!.source = source
              })
            }
          />
          <RowActions
            index={index}
            length={value.flashcards.length}
            canRemove={value.flashcards.length > 10}
            canDuplicate={value.flashcards.length < 20}
            onMove={(offset) =>
              change((draft) => {
                draft.flashcards = move(draft.flashcards, index, offset)
              })
            }
            onDuplicate={() =>
              change((draft) => {
                const copy = clone(draft.flashcards[index]!)
                copy.id = newId('card')
                draft.flashcards.splice(index + 1, 0, copy)
              })
            }
            onRemove={() =>
              change((draft) => {
                draft.flashcards.splice(index, 1)
              })
            }
          />
        </article>
      ))}

      <h2>Quiz</h2>
      {value.quiz.map((question, index) => (
        <article className="editor-card" key={question.id}>
          <h3>Câu {index + 1}</h3>
          <label>
            Câu hỏi
            <textarea
              required
              maxLength={1000}
              value={question.question}
              onChange={(event) =>
                change((draft) => {
                  draft.quiz[index]!.question = event.target.value
                })
              }
            />
          </label>
          {question.options.map((option, optionIndex) => (
            <label key={optionIndex}>
              <input
                type="radio"
                name={`correct-${question.id}`}
                checked={question.correctAnswerIndex === optionIndex}
                onChange={() =>
                  change((draft) => {
                    draft.quiz[index]!.correctAnswerIndex = optionIndex
                  })
                }
              />
              Đáp án {optionIndex + 1}
              <input
                required
                maxLength={1000}
                value={option}
                onChange={(event) =>
                  change((draft) => {
                    draft.quiz[index]!.options[optionIndex] = event.target.value
                  })
                }
              />
            </label>
          ))}
          <label>
            Giải thích
            <textarea
              required
              maxLength={2000}
              value={question.explanation}
              onChange={(event) =>
                change((draft) => {
                  draft.quiz[index]!.explanation = event.target.value
                })
              }
            />
          </label>
          <SourceFields
            value={question.source}
            onChange={(source) =>
              change((draft) => {
                draft.quiz[index]!.source = source
              })
            }
          />
          <RowActions
            index={index}
            length={value.quiz.length}
            canRemove={value.quiz.length > 5}
            canDuplicate={value.quiz.length < 10}
            onMove={(offset) =>
              change((draft) => {
                draft.quiz = move(draft.quiz, index, offset)
              })
            }
            onDuplicate={() =>
              change((draft) => {
                const copy = clone(draft.quiz[index]!)
                copy.id = newId('quiz')
                draft.quiz.splice(index + 1, 0, copy)
              })
            }
            onRemove={() =>
              change((draft) => {
                draft.quiz.splice(index, 1)
              })
            }
          />
        </article>
      ))}
    </fieldset>
  )
}
