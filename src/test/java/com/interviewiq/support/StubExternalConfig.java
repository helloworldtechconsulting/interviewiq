package com.interviewiq.support;

import com.razorpay.RazorpayClient;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Stubs for non-AWS external SDKs used by InterviewIQ.
 *
 * <p>Razorpay is mocked so wallet top-up tests can stub a fake order ID
 * without ever calling the real payment gateway.
 *
 * <p>Recall.ai and Spring AI's {@code ChatClient} are intentionally NOT
 * declared here — Spring AI auto-configuration tolerates the dummy API key
 * provided in application-test.yml, and the few tests that exercise the
 * AI / bot integration override those beans via {@code @MockBean}.
 */
@TestConfiguration
public class StubExternalConfig {

    @Bean
    @Primary
    public RazorpayClient testRazorpayClient() {
        // Mockito.mock with deep stubs so chained calls like client.orders.create(...) work
        return Mockito.mock(RazorpayClient.class, Mockito.RETURNS_DEEP_STUBS);
    }
}
