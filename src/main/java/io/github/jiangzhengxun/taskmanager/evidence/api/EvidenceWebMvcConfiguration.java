package io.github.jiangzhengxun.taskmanager.evidence.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Adds the Evidence-only read converter in front of the default list. Existing converters keep their
 * relative order; the default list is extended, not replaced; no @EnableWebMvc.
 */
@Configuration(proxyBeanMethods = false)
public class EvidenceWebMvcConfiguration implements WebMvcConfigurer {

    private final ObjectMapper sharedObjectMapper;

    public EvidenceWebMvcConfiguration(ObjectMapper sharedObjectMapper) {
        this.sharedObjectMapper = Objects.requireNonNull(sharedObjectMapper, "sharedObjectMapper must not be null");
    }

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.add(0, new EvidenceRequestBodyConverter(sharedObjectMapper.copy()));
    }
}
