import { useState } from 'react'
import { Link } from 'react-router-dom'
import logo from '../assets/logo/full_horizontal.png'

const sections = [
  {
    timestamp: '00:00',
    title: 'Ghi nhận trước khi giải quyết',
    content:
      'Nhắc lại vấn đề bằng ngôn ngữ trung lập, xác nhận tác động và chỉ cam kết mốc phản hồi mà đội ngũ thực sự kiểm soát được.',
    evidence:
      '“Xác nhận điều khách hàng đang gặp trước khi đề xuất hướng xử lý.”',
  },
  {
    timestamp: '02:25',
    title: 'Phân loại mức độ ảnh hưởng',
    content:
      'Ưu tiên theo mức độ gián đoạn và số người bị ảnh hưởng. Không dùng mức độ bức xúc làm tiêu chí duy nhất.',
    evidence: '“Tách cảm xúc khỏi mức độ ảnh hưởng vận hành.”',
  },
  {
    timestamp: '05:10',
    title: 'Khép vòng phản hồi',
    content:
      'Thông báo kết quả, xác nhận khách hàng đã sử dụng lại được và ghi nguyên nhân để tránh lặp lại.',
    evidence: '“Một ticket chỉ hoàn tất khi kết quả đã được xác nhận.”',
  },
]

const quizOptions = [
  'Hứa xử lý ngay để khách hàng yên tâm',
  'Xác nhận vấn đề, tác động và mốc phản hồi kiểm soát được',
  'Chuyển ticket cho bộ phận khác mà không cần phản hồi',
  'Ưu tiên hoàn toàn theo mức độ bức xúc',
]

export function SampleCoursePage() {
  const [answerVisible, setAnswerVisible] = useState(false)
  const [selectedOption, setSelectedOption] = useState<number | null>(null)
  const [quizChecked, setQuizChecked] = useState(false)

  return (
    <main className="sample-course-page">
      <nav aria-label="Điều hướng bài học mẫu">
        <Link to="/" aria-label="Về trang chủ Vid2Knowledge">
          <img src={logo} alt="Vid2Knowledge" />
        </Link>
        <div className="nav-actions">
          <Link to="/">Trang chủ</Link>
          <Link className="button-link" to="/login?source=sample-course">
            Dùng video của đội ngũ
          </Link>
        </div>
      </nav>

      <header className="sample-heading">
        <div>
          <p className="eyebrow">BÀI HỌC MẪU · NỘI DUNG MINH HỌA</p>
          <h1>Tiếp nhận và xử lý phản hồi khách hàng</h1>
          <p className="lead">
            Bản mẫu cho thấy cấu trúc đầu ra sau khi người dạy đã kiểm duyệt. Nó
            không dùng quota, không được ghi vào tiến độ và không đại diện cho
            kết quả của một khách hàng thật.
          </p>
          <div className="metrics" aria-label="Thành phần bài học mẫu">
            <span>8 phút video giả lập</span>
            <span>3 phần có bằng chứng</span>
            <span>Flashcard + quiz</span>
          </div>
        </div>
        <aside className="sample-watermark" aria-label="Giới hạn bản mẫu">
          <strong>SAMPLE</strong>
          <span>Không chứa dữ liệu khách hàng</span>
          <span>Không tính activation hoặc usage</span>
          <span>Không gọi AI</span>
        </aside>
      </header>

      <section className="sample-content" aria-labelledby="sample-notes-title">
        <div>
          <p className="eyebrow">GHI CHÚ CÓ THỂ KIỂM CHỨNG</p>
          <h2 id="sample-notes-title">Từ video đến các ý có nguồn</h2>
          <p>
            Người học thấy nội dung cô đọng; người dạy vẫn có timestamp và trích
            đoạn để kiểm tra trước khi xuất bản.
          </p>
        </div>
        <div className="sample-section-list">
          {sections.map((section) => (
            <article key={section.timestamp}>
              <span className="timestamp">{section.timestamp}</span>
              <div>
                <h3>{section.title}</h3>
                <p>{section.content}</p>
                <small>Bằng chứng mẫu: {section.evidence}</small>
              </div>
            </article>
          ))}
        </div>
      </section>

      <section
        className="sample-practice"
        aria-labelledby="sample-practice-title"
      >
        <div>
          <p className="eyebrow">LUYỆN NHỚ CHỦ ĐỘNG</p>
          <h2 id="sample-practice-title">Thử một flashcard</h2>
          <article className="sample-flashcard">
            <small>Câu hỏi · nguồn 02:25</small>
            <strong>Tiêu chí chính để ưu tiên phản hồi là gì?</strong>
            {answerVisible ? (
              <p>
                Mức độ gián đoạn và số người bị ảnh hưởng, thay vì chỉ dựa vào
                mức độ bức xúc.
              </p>
            ) : (
              <button onClick={() => setAnswerVisible(true)}>
                Hiện đáp án
              </button>
            )}
          </article>
        </div>

        <div>
          <p className="eyebrow">KIỂM TRA HIỂU BÀI</p>
          <h2>Thử một câu quiz</h2>
          <fieldset className="sample-quiz">
            <legend>Phản hồi đầu tiên nên làm gì?</legend>
            {quizOptions.map((option, index) => (
              <label key={option}>
                <input
                  type="radio"
                  name="sample-quiz"
                  checked={selectedOption === index}
                  onChange={() => {
                    setSelectedOption(index)
                    setQuizChecked(false)
                  }}
                />
                {option}
              </label>
            ))}
            <button
              disabled={selectedOption === null}
              onClick={() => setQuizChecked(true)}
            >
              Kiểm tra đáp án
            </button>
            {quizChecked && (
              <p
                className={
                  selectedOption === 1 ? 'sample-correct' : 'form-error'
                }
                role="status"
              >
                {selectedOption === 1
                  ? 'Chính xác. Cam kết phải gắn với mốc mà đội ngũ kiểm soát được.'
                  : 'Chưa đúng. Hãy xác nhận vấn đề, tác động và một mốc phản hồi thực tế.'}
              </p>
            )}
          </fieldset>
        </div>
      </section>

      <section className="sample-conversion">
        <div>
          <p className="eyebrow">BƯỚC TIẾP THEO</p>
          <h2>Tạo bản đã kiểm duyệt từ nội dung của chính đội ngũ.</h2>
          <p>
            Trial dùng dữ liệu canonical riêng của tổ chức; bài mẫu này sẽ không
            xuất hiện trong báo cáo hay làm đẹp chỉ số kích hoạt.
          </p>
        </div>
        <Link className="button-link" to="/login?source=sample-course">
          Bắt đầu trial 60 phút
        </Link>
      </section>
    </main>
  )
}
