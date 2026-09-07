package com.vid2knowledge.delivery;

import com.vid2knowledge.auth.CurrentActor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PackageExportServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");

    @Test
    void rendersVietnameseMarkdownAndStandardsBasedWordPackage() throws Exception {
        JsonNode content = content();
        String markdown = PackageExportService.markdown(content, "APPROVED", 3);
        assertThat(markdown)
                .contains("# Đào tạo an toàn &amp; hiệu quả", "## Flashcard", "**B. Đáp án đúng**", "2:05")
                .doesNotContain("<script>");

        byte[] bytes = PackageExportService.word(content, "APPROVED", 3);
        Map<String, String> entries = unzip(bytes);
        assertThat(entries).containsKeys("[Content_Types].xml", "_rels/.rels", "word/document.xml");
        assertThat(entries.get("word/document.xml"))
                .contains("Đào tạo an toàn &amp; hiệu quả", "✓ B. Đáp án đúng", "Revision 3")
                .doesNotContain("<script>");
    }

    @Test
    void reservesWordForPaidTeamButKeepsMarkdownPortable() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PackageWorkflowService packages = mock(PackageWorkflowService.class);
        UUID organizationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID packageId = UUID.randomUUID();
        CurrentActor actor = new CurrentActor(userId, organizationId, CurrentActor.Role.OWNER);
        when(packages.get(organizationId, packageId)).thenReturn(new PackageWorkflowService.PackageView(
                packageId, UUID.randomUUID(), "APPROVED", 1, UUID.randomUUID(), 3,
                "HUMAN_VERIFIED", content()
        ));
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(), any())).thenReturn(false);
        var service = new PackageExportService(
                jdbc, packages, Clock.fixed(NOW, ZoneOffset.UTC)
        );

        assertThatThrownBy(() -> service.export(actor, packageId, PackageExportService.Format.WORD, "corr-word"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(failure -> ((ResponseStatusException) failure).getStatusCode())
                .isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        verify(packages, never()).get(organizationId, packageId);

        var markdown = service.export(actor, packageId, PackageExportService.Format.MARKDOWN, "corr-md");
        assertThat(markdown.mediaType()).startsWith("text/markdown");
        assertThat(new String(markdown.body(), StandardCharsets.UTF_8)).contains("Đào tạo an toàn");
        verify(jdbc).update(anyString(), any(Object[].class));
    }

    private static JsonNode content() {
        return new ObjectMapper().readTree(
                """
                {
                  "video": {"title": "Đào tạo an toàn & hiệu quả"},
                  "summary": {
                    "overview": "Tổng quan <script>alert(1)</script>",
                    "sections": [{
                      "title": "Phần một",
                      "content": ["Nội dung chính"],
                      "source": {"timestampSeconds": 125, "evidence": "Bằng chứng"}
                    }]
                  },
                  "keyTakeaways": [{
                    "text": "Điểm cần nhớ",
                    "source": {"timestampSeconds": 125, "evidence": "Bằng chứng"}
                  }],
                  "flashcards": [{
                    "question": "Câu hỏi?",
                    "answer": "Câu trả lời",
                    "source": {"timestampSeconds": 125, "evidence": "Bằng chứng"}
                  }],
                  "quiz": [{
                    "question": "Chọn đáp án",
                    "options": ["Sai A", "Đáp án đúng", "Sai C", "Sai D"],
                    "correctAnswerIndex": 1,
                    "explanation": "Vì lý do này",
                    "source": {"timestampSeconds": 125, "evidence": "Bằng chứng"}
                  }]
                }
                """
        );
    }

    private static Map<String, String> unzip(byte[] bytes) throws Exception {
        Map<String, String> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }
}
