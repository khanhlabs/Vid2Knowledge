import { Link } from 'react-router-dom'
import logo from '../assets/logo/full_horizontal.png'

export function LandingPage() {
  return (
    <main className="landing">
      <nav>
        <img src={logo} alt="Vid2Knowledge" />
        <div className="nav-actions">
          <Link to="/login">Đăng nhập</Link>
          <Link className="button-link" to="/login">
            Dùng thử cho đội ngũ
          </Link>
        </div>
      </nav>
      <section className="hero">
        <div>
          <p className="eyebrow">VIDEO ĐÀO TẠO → KẾT QUẢ HỌC TẬP</p>
          <h1>Biến thư viện video thành chương trình học có thể đo lường.</h1>
          <p className="lead">
            Vid2Knowledge giúp học viện và đội ngũ đào tạo tạo học liệu, giao
            bài, chấm kiến thức và theo dõi hoàn thành—không phải viết lại từng
            flashcard và câu hỏi bằng tay.
          </p>
          <div className="hero-actions">
            <Link className="button-link" to="/login">
              Bắt đầu trial 60 phút
            </Link>
            <a href="#pricing">Xem gói cho đội ngũ</a>
          </div>
          <p className="consent">
            Chỉ xử lý video công khai mà tổ chức có quyền sử dụng. Học liệu AI
            luôn qua bước kiểm duyệt trước khi xuất bản.
          </p>
        </div>
        <aside>
          <p className="aside-label">MỘT VÒNG LẶP HOÀN CHỈNH</p>
          <ol className="workflow-list">
            <li>
              <span>01</span>
              <div>
                <strong>Tạo học liệu</strong>
                <small>Tóm tắt, flashcard, quiz có nguồn.</small>
              </div>
            </li>
            <li>
              <span>02</span>
              <div>
                <strong>Người dạy kiểm duyệt</strong>
                <small>Phiên bản rõ ràng, không publish nhầm.</small>
              </div>
            </li>
            <li>
              <span>03</span>
              <div>
                <strong>Giao cho cohort</strong>
                <small>Học viên học và làm bài trên web.</small>
              </div>
            </li>
            <li>
              <span>04</span>
              <div>
                <strong>Đo outcome</strong>
                <small>Hoàn thành và điểm số từ dữ liệu thật.</small>
              </div>
            </li>
          </ol>
        </aside>
      </section>
      <section className="proof-strip">
        <span>Quota theo phút, không “unlimited”</span>
        <span>Phân quyền theo tổ chức</span>
        <span>Chấm quiz phía server</span>
        <span>Chi phí AI được đo cho từng job</span>
      </section>
      <section id="pricing" className="pricing-section">
        <p className="eyebrow">GIÁ KIỂM CHỨNG CHO VIỆT NAM</p>
        <h2>Chi phí gắn với quy mô đào tạo.</h2>
        <div className="pricing-grid">
          <article>
            <h3>Creator</h3>
            <p className="price">
              790.000₫<small>/tháng</small>
            </p>
            <p>300 phút nguồn · 3 người dạy · 200 học viên</p>
          </article>
          <article className="featured">
            <span className="badge">KHUYẾN NGHỊ</span>
            <h3>Training Team</h3>
            <p className="price">
              2.490.000₫<small>/tháng</small>
            </p>
            <p>1.500 phút nguồn · 10 người dạy · 1.000 học viên</p>
          </article>
          <article>
            <h3>Business</h3>
            <p className="price">Từ 7,99 triệu</p>
            <p>Onboarding, API, audit và SLA có giới hạn rõ.</p>
          </article>
        </div>
      </section>
    </main>
  )
}
