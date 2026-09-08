package com.vid2knowledge.sales;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PilotLeadControllerTest {

    @Test
    void publicSubmissionDoesNotExposeInternalPriority() throws Exception {
        PilotLeadService leads = mock(PilotLeadService.class);
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        Instant receivedAt = Instant.parse("2026-09-08T00:00:00Z");
        UUID leadId = UUID.randomUUID();
        var request = new PilotLeadController.SubmitRequest(
                "Nguyen Minh", "minh@example.vn", "Hoc vien Minh",
                PilotLeadService.BuyerRole.TRAINING_MANAGER,
                PilotLeadService.Minutes.BETWEEN_300_599,
                PilotLeadService.Learners.BETWEEN_50_199,
                PilotLeadService.Goal.PROVE_LEARNING, null,
                PilotLeadService.Source.PARTNER, "trainer-network", true
        );
        when(servletRequest.getRemoteAddr()).thenReturn("127.0.0.1");
        when(servletRequest.getHeader("User-Agent")).thenReturn("test-agent");
        when(leads.submit(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("pilot-lead-idempotency-001"),
                org.mockito.ArgumentMatchers.eq("127.0.0.1|test-agent")
        )).thenReturn(new PilotLeadService.Submission(leadId, "HOT", receivedAt));

        var response = new PilotLeadController(leads).submit(
                "pilot-lead-idempotency-001", request, servletRequest
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(new PilotLeadController.PublicSubmission(leadId, receivedAt));
        assertThat(Arrays.stream(response.getBody().getClass().getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .containsExactly("id", "receivedAt");
    }

    @Test
    void internalSalesResponsesCannotBeCached() {
        PilotLeadService leads = mock(PilotLeadService.class);
        when(leads.queue(null)).thenReturn(List.of());
        when(leads.funnel()).thenReturn(List.of());
        var controller = new InternalPilotLeadController(leads);

        assertThat(controller.queue(null).getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(controller.funnel().getHeaders().getCacheControl()).isEqualTo("no-store");
    }
}
