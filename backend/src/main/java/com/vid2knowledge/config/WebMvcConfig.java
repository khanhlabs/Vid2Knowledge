package com.vid2knowledge.config;

import com.vid2knowledge.common.api.RateLimitInterceptor;
import com.vid2knowledge.legal.LegalAcceptanceInterceptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    private final RateLimitInterceptor rateLimitInterceptor;
    private final ObjectProvider<LegalAcceptanceInterceptor> legalAcceptanceInterceptor;

    public WebMvcConfig(
            RateLimitInterceptor rateLimitInterceptor,
            ObjectProvider<LegalAcceptanceInterceptor> legalAcceptanceInterceptor
    ) {
        this.rateLimitInterceptor = rateLimitInterceptor;
        this.legalAcceptanceInterceptor = legalAcceptanceInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor);
        legalAcceptanceInterceptor.ifAvailable(interceptor -> registry.addInterceptor(interceptor));
    }
}
