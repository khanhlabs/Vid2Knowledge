package com.vid2knowledge.notification;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InternalNotificationControllerTest {
    @Test
    void reminderDiscoveryFailureDoesNotBlockTransactionalEmailDispatch() {
        NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);
        ReminderSchedulingService reminders = mock(ReminderSchedulingService.class);
        var expected = new NotificationDispatcher.DispatchResult(1, 1, 0, 0, 0);
        when(reminders.schedule()).thenThrow(new IllegalStateException("temporary reminder query failure"));
        when(dispatcher.dispatch(anyString())).thenReturn(expected);

        var result = new InternalNotificationController(dispatcher, reminders).dispatch();

        assertThat(result).isEqualTo(expected);
        verify(dispatcher).dispatch(anyString());
    }
}
