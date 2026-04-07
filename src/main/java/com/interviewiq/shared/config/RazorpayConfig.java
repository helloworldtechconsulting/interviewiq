package com.interviewiq.shared.config;

import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures the Razorpay Java SDK client as a Spring bean.
 *
 * <p>The {@link RazorpayClient} is thread-safe and expensive to construct
 * (makes an auth check on startup). Declare it once and inject everywhere.
 */
@Configuration
public class RazorpayConfig {

    private final RazorpayProperties props;

    public RazorpayConfig(RazorpayProperties props) {
        this.props = props;
    }

    @Bean
    public RazorpayClient razorpayClient() throws RazorpayException {
        return new RazorpayClient(props.getKeyId(), props.getKeySecret());
    }
}
