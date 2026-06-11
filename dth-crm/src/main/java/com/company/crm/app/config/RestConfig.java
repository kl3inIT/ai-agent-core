package com.company.crm.app.config;

import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.company.crm.app.util.proxy.ProxyUtils.configureProxy;

@Configuration
public class RestConfig {

    @Bean
    @Primary
    public RestClient restClient(ApplicationContext applicationContext) {
        return makeRestClientBuilder(applicationContext, false).build();
    }

    public static RestClient.Builder makeRestClientBuilder(ApplicationContext applicationContext, boolean useProxy) {
        return makeRestClientBuilder(RestClient.builder(), applicationContext, useProxy);
    }

    public static RestClient.Builder makeRestClientBuilder(RestClient.Builder builder,
                                                           ApplicationContext applicationContext,
                                                           boolean useProxy) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000); // 5 seconds to establish connection

        RestClient.Builder configuredBuilder = builder
                .requestFactory(factory)
                .messageConverters(converters -> converters.addAll(getMessageConverters()));

        if (useProxy) {
            configureProxy(applicationContext.getBean(HttpClientProxyConfiguration.class), factory);
        }

        return configuredBuilder;
    }

    private static List<HttpMessageConverter<?>> getMessageConverters() {
        return List.of(
                createJacksonMapper(),
                new StringHttpMessageConverter(StandardCharsets.UTF_8),
                new FormHttpMessageConverter(),
                new ByteArrayHttpMessageConverter()
        );
    }

    private static MappingJackson2HttpMessageConverter createJacksonMapper() {
        MappingJackson2HttpMessageConverter jackson2HttpMessageConverter = new MappingJackson2HttpMessageConverter();
        jackson2HttpMessageConverter.setSupportedMediaTypes(List.of(MediaType.ALL));
        return jackson2HttpMessageConverter;
    }
}