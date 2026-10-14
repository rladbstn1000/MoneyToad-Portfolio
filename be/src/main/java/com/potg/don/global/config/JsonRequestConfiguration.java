package com.potg.don.global.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.potg.don.transaction.dto.request.UpdateCategoryRequest;
import com.potg.don.card.dto.request.CardRequest;

/** Reject a second JSON value only at explicitly selected request boundaries. */
@Configuration(proxyBeanMethods = false)
public class JsonRequestConfiguration implements WebMvcConfigurer {

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (var converter : converters) {
            if (converter instanceof MappingJackson2HttpMessageConverter json) {
                var strict = json.getObjectMapper().copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
                json.registerObjectMappersForType(UpdateCategoryRequest.class, registrations ->
                    json.getSupportedMediaTypes().forEach(mediaType -> registrations.put(mediaType, strict)));
                json.registerObjectMappersForType(CardRequest.class, registrations ->
                    json.getSupportedMediaTypes().forEach(mediaType -> registrations.put(mediaType, strict)));
            }
        }
    }
}
