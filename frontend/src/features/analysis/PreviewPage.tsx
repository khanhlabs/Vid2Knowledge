import { zodResolver } from '@hookform/resolvers/zod'
import { useSessionMutation as useMutation } from '../../shared/hooks/useSessionMutation'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import logo from '../../assets/logo/full_horizontal.png'
import { createPreview } from './api'

const formSchema = z.object({
  youtubeUrl: z.url('Hãy nhập một đường dẫn hợp lệ.').refine((value) => {
    const host = new URL(value).hostname.toLowerCase()
    return (
      host === 'youtube.com' ||
      host.endsWith('.youtube.com') ||
      host === 'youtu.be'
    )
  }, 'Hiện tại Vid2Knowledge chỉ hỗ trợ video YouTube.'),
})

type FormValues = z.infer<typeof formSchema>

export function PreviewPage() {
  const form = useForm<FormValues>({
    resolver: zodResolver(formSchema),
    defaultValues: { youtubeUrl: '' },
  })
  const preview = useMutation({ mutationFn: createPreview })

  const submit = form.handleSubmit(async ({ youtubeUrl }) => {
    await preview.mutateAsync(youtubeUrl)
  })

  return (
    <main>
      <nav aria-label="Điều hướng chính">
        <img src={logo} alt="Vid2Knowledge" />
        <span>Phiên bản thử nghiệm cho đội ngũ đào tạo</span>
      </nav>

      <section className="hero">
        <div>
          <p className="eyebrow">
            HỌC LIỆU TỪ VIDEO, KHÔNG PHẢI THÊM VIỆC THỦ CÔNG
          </p>
          <h1>Biến một video đào tạo thành bộ học liệu có thể sử dụng ngay.</h1>
          <p className="lead">
            Tạo tóm tắt, ý chính, flashcard và câu hỏi kiểm tra bằng AI. Nội
            dung luôn cần được người phụ trách duyệt trước khi giao cho học
            viên.
          </p>

          <form onSubmit={(event) => void submit(event)} noValidate>
            <label htmlFor="youtubeUrl">Đường dẫn video YouTube</label>
            <div className="input-row">
              <input
                id="youtubeUrl"
                type="url"
                placeholder="https://www.youtube.com/watch?v=..."
                aria-invalid={Boolean(form.formState.errors.youtubeUrl)}
                {...form.register('youtubeUrl')}
              />
              <button type="submit" disabled={preview.isPending}>
                {preview.isPending ? 'Đang tạo học liệu…' : 'Tạo bản xem trước'}
              </button>
            </div>
            {form.formState.errors.youtubeUrl && (
              <p className="form-error">
                {form.formState.errors.youtubeUrl.message}
              </p>
            )}
            {preview.isError && (
              <p className="form-error" role="alert">
                {preview.error.message}
              </p>
            )}
            <p className="consent">
              Chỉ gửi video bạn có quyền sử dụng cho mục đích đào tạo.
            </p>
          </form>
        </div>

        <aside>
          <p className="aside-label">ĐẦU RA CÓ CẤU TRÚC</p>
          <ul>
            <li>
              <strong>Tóm tắt theo phần</strong>
              <span>Nắm nhanh cấu trúc và nội dung chính.</span>
            </li>
            <li>
              <strong>Flashcard ôn tập</strong>
              <span>Biến kiến thức thụ động thành ghi nhớ chủ động.</span>
            </li>
            <li>
              <strong>Câu hỏi kiểm tra</strong>
              <span>Đánh giá mức độ hiểu ngay sau bài học.</span>
            </li>
          </ul>
        </aside>
      </section>

      {preview.data && (
        <section className="result" aria-live="polite">
          <p className="eyebrow">BẢN XEM TRƯỚC DO AI TẠO — CẦN KIỂM DUYỆT</p>
          <h2>{preview.data.video.title}</h2>
          <p>{preview.data.summary.overview}</p>
          <div className="metrics">
            <span>{preview.data.summary.sections.length} phần nội dung</span>
            <span>{preview.data.flashcards.length} flashcard</span>
            <span>{preview.data.quiz.length} câu hỏi</span>
          </div>
          <h3>Ý chính</h3>
          <ul>
            {preview.data.keyTakeaways.map((item) => (
              <li key={item.id}>
                {item.text} <small>· {item.source.timestampSeconds}s</small>
              </li>
            ))}
          </ul>
        </section>
      )}
    </main>
  )
}
