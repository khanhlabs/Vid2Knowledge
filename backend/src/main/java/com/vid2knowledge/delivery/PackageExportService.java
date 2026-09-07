package com.vid2knowledge.delivery;

import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.common.id.UuidV7Generator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class PackageExportService {
    private final JdbcTemplate jdbc;
    private final PackageWorkflowService packages;
    private final Clock clock;

    @Autowired
    public PackageExportService(JdbcTemplate jdbc, PackageWorkflowService packages) {
        this(jdbc, packages, Clock.systemUTC());
    }

    PackageExportService(JdbcTemplate jdbc, PackageWorkflowService packages, Clock clock) {
        this.jdbc = jdbc;
        this.packages = packages;
        this.clock = clock;
    }

    public ExportedFile export(CurrentActor actor, UUID packageId, Format format, String correlationId) {
        if (format == Format.WORD) requireTeamPlan(actor.organizationId());
        PackageWorkflowService.PackageView item = packages.get(actor.organizationId(), packageId);
        byte[] body = format == Format.MARKDOWN
                ? markdown(item.content(), item.state(), item.revisionNo()).getBytes(StandardCharsets.UTF_8)
                : word(item.content(), item.state(), item.revisionNo());
        audit(actor, packageId, format, correlationId);
        return new ExportedFile(body, format.mediaType, format.filename);
    }

    private void requireTeamPlan(UUID organizationId) {
        Boolean entitled = jdbc.queryForObject(
                """
                SELECT EXISTS(
                    SELECT 1 FROM subscriptions s JOIN pricing_plans p ON p.id = s.plan_id
                    WHERE s.organization_id = ? AND s.status IN ('ACTIVE', 'PAST_DUE')
                      AND s.current_period_end > ?
                      AND (p.code LIKE 'TRAINING\\_TEAM\\_%' ESCAPE '\\'
                           OR p.code LIKE 'BUSINESS\\_%' ESCAPE '\\')
                )
                """,
                Boolean.class, organizationId, Timestamp.from(clock.instant())
        );
        if (!Boolean.TRUE.equals(entitled)) {
            throw new ResponseStatusException(
                    HttpStatus.PAYMENT_REQUIRED, "Word export requires an active Training Team or Business plan"
            );
        }
    }

    static String markdown(JsonNode content, String state, int revision) {
        StringBuilder out = new StringBuilder();
        out.append("# ").append(markdownText(content.path("video"), "title", "Học liệu")).append("\n\n")
                .append("> Trạng thái: ").append(state).append(" · Revision ").append(revision).append("\n\n")
                .append("## Tổng quan\n\n")
                .append(markdownText(content.path("summary"), "overview", "")).append("\n\n");
        for (JsonNode section : content.path("summary").path("sections")) {
            out.append("### ").append(markdownText(section, "title", "Nội dung")).append("\n\n");
            for (JsonNode paragraph : section.path("content")) out.append(markdown(paragraph.asText())).append("\n\n");
            out.append("> Nguồn: ").append(timestamp(section.path("source").path("timestampSeconds").asLong()))
                    .append(" — ").append(markdownText(section.path("source"), "evidence", "")).append("\n\n");
        }
        out.append("## Điểm chính\n\n");
        for (JsonNode item : content.path("keyTakeaways")) {
            out.append("- ").append(markdownText(item, "text", ""))
                    .append(markdown(inlineSource(item.path("source")))).append('\n');
        }
        out.append("\n## Flashcard\n\n");
        int cardNumber = 1;
        for (JsonNode card : content.path("flashcards")) {
            out.append("### Thẻ ").append(cardNumber++).append("\n\n**Hỏi:** ")
                    .append(markdownText(card, "question", "")).append("\n\n**Đáp:** ")
                    .append(markdownText(card, "answer", ""))
                    .append(markdown(inlineSource(card.path("source")))).append("\n\n");
        }
        out.append("## Câu hỏi kiểm tra\n\n");
        int questionNumber = 1;
        for (JsonNode question : content.path("quiz")) {
            out.append("### Câu ").append(questionNumber++).append(". ")
                    .append(markdownText(question, "question", "")).append("\n\n");
            int answer = question.path("correctAnswerIndex").asInt(-1);
            int optionIndex = 0;
            for (JsonNode option : question.path("options")) {
                out.append(optionIndex == answer ? "- **" : "- ").append((char) ('A' + optionIndex)).append(". ")
                        .append(markdown(option.asText())).append(optionIndex == answer ? "**" : "").append('\n');
                optionIndex++;
            }
            out.append("\n**Giải thích:** ").append(markdownText(question, "explanation", ""))
                    .append(markdown(inlineSource(question.path("source")))).append("\n\n");
        }
        return out.append("---\nXuất từ Vid2Knowledge. Hãy kiểm duyệt nội dung AI trước khi sử dụng.\n").toString();
    }

    static byte[] word(JsonNode content, String state, int revision) {
        StringBuilder document = new StringBuilder(
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        );
        paragraph(document, text(content.path("video"), "title", "Học liệu"), true, 36);
        paragraph(document, "Trạng thái: " + state + " · Revision " + revision, false, 18);
        heading(document, "Tổng quan", 30);
        paragraph(document, text(content.path("summary"), "overview", ""), false, 22);
        for (JsonNode section : content.path("summary").path("sections")) {
            heading(document, text(section, "title", "Nội dung"), 26);
            for (JsonNode item : section.path("content")) paragraph(document, item.asText(), false, 22);
            paragraph(document, "Nguồn" + inlineSource(section.path("source")), false, 18);
        }
        heading(document, "Điểm chính", 30);
        for (JsonNode item : content.path("keyTakeaways")) {
            paragraph(document, "• " + text(item, "text", "") + inlineSource(item.path("source")), false, 22);
        }
        heading(document, "Flashcard", 30);
        int cardNumber = 1;
        for (JsonNode card : content.path("flashcards")) {
            paragraph(document, "Thẻ " + cardNumber++ + " — " + text(card, "question", ""), true, 22);
            paragraph(document, text(card, "answer", "") + inlineSource(card.path("source")), false, 22);
        }
        heading(document, "Câu hỏi kiểm tra", 30);
        int questionNumber = 1;
        for (JsonNode question : content.path("quiz")) {
            paragraph(document, "Câu " + questionNumber++ + ". " + text(question, "question", ""), true, 22);
            int answer = question.path("correctAnswerIndex").asInt(-1);
            int optionIndex = 0;
            for (JsonNode option : question.path("options")) {
                paragraph(document, (optionIndex == answer ? "✓ " : "○ ")
                        + (char) ('A' + optionIndex) + ". " + option.asText(), false, 22);
                optionIndex++;
            }
            paragraph(document, "Giải thích: " + text(question, "explanation", "")
                    + inlineSource(question.path("source")), false, 20);
        }
        paragraph(document, "Xuất từ Vid2Knowledge. Hãy kiểm duyệt nội dung AI trước khi sử dụng.", false, 18);
        document.append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>"
                + "<w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\"/>"
                + "</w:sectPr></w:body></w:document>");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
                entry(zip, "[Content_Types].xml",
                        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                                + "</Types>");
                entry(zip, "_rels/.rels",
                        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                                + "</Relationships>");
                entry(zip, "word/document.xml", document.toString());
            }
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException("Could not create Word export", failure);
        }
    }

    private static void heading(StringBuilder target, String value, int size) {
        paragraph(target, value, true, size);
    }

    private static void paragraph(StringBuilder target, String value, boolean bold, int size) {
        target.append("<w:p><w:r><w:rPr>");
        if (bold) target.append("<w:b/>");
        target.append("<w:sz w:val=\"").append(size).append("\"/></w:rPr><w:t xml:space=\"preserve\">")
                .append(xml(value)).append("</w:t></w:r></w:p>");
    }

    private static String inlineSource(JsonNode source) {
        return " (" + timestamp(source.path("timestampSeconds").asLong()) + ": "
                + text(source, "evidence", "") + ")";
    }

    private static String timestamp(long seconds) {
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private static String text(JsonNode node, String field, String fallback) {
        return node.path(field).asText(fallback);
    }

    private static String markdownText(JsonNode node, String field, String fallback) {
        return markdown(text(node, field, fallback));
    }

    private static String markdown(String value) {
        return value.replace("\\", "\\\\").replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;")
                .replace("[", "\\[").replace("]", "\\]");
    }

    private static String xml(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            if (codePoint == '&') escaped.append("&amp;");
            else if (codePoint == '<') escaped.append("&lt;");
            else if (codePoint == '>') escaped.append("&gt;");
            else if (codePoint == '"') escaped.append("&quot;");
            else if (codePoint == '\'') escaped.append("&apos;");
            else if (codePoint == '\t' || codePoint == '\n' || codePoint == '\r'
                    || codePoint >= 0x20 && codePoint <= 0xd7ff
                    || codePoint >= 0xe000 && codePoint <= 0xfffd
                    || codePoint >= 0x10000 && codePoint <= 0x10ffff) escaped.appendCodePoint(codePoint);
        });
        return escaped.toString();
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private void audit(CurrentActor actor, UUID packageId, Format format, String correlationId) {
        jdbc.update(
                """
                INSERT INTO audit_logs(
                    id, organization_id, actor_user_id, action, resource_type,
                    resource_id, metadata_json, correlation_id, created_at
                ) VALUES (?, ?, ?, 'PACKAGE_EXPORTED', 'LearningPackage', ?,
                          jsonb_build_object('format', ?), ?, ?)
                """,
                UuidV7Generator.generate(), actor.organizationId(), actor.userId(), packageId,
                format.name(), correlationId, Timestamp.from(clock.instant())
        );
    }

    public enum Format {
        MARKDOWN("text/markdown;charset=UTF-8", "hoc-lieu.md"),
        WORD("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "hoc-lieu.docx");

        private final String mediaType;
        private final String filename;

        Format(String mediaType, String filename) {
            this.mediaType = mediaType;
            this.filename = filename;
        }
    }

    public record ExportedFile(byte[] body, String mediaType, String filename) { }
}
