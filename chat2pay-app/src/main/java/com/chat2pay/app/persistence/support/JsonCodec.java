package com.chat2pay.app.persistence.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collection;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class JsonCodec {

    private final ObjectMapper objectMapper;

    public JsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String writeNullable(Object value) {
        return writeNullable(value, null);
    }

    public String writeNullable(Object value, TypeReference<?> typeReference) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map && map.isEmpty()) {
            return null;
        }
        if (value instanceof Collection<?> collection && collection.isEmpty()) {
            return null;
        }
        try {
            if (typeReference == null) {
                return objectMapper.writeValueAsString(value);
            }
            return objectMapper.writerFor(objectMapper.constructType(typeReference)).writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize JSON payload.", ex);
        }
    }

    public <T> T read(String json, TypeReference<T> typeReference, Supplier<T> defaultSupplier) {
        if (json == null || json.isBlank()) {
            return defaultSupplier.get();
        }
        try {
            return objectMapper.readValue(json, typeReference);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize JSON payload.", ex);
        }
    }

    public <T> T convert(Object value, Class<T> targetType) {
        try {
            return objectMapper.convertValue(value, targetType);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Failed to convert JSON payload.", ex);
        }
    }

    public <T> T convert(Object value, TypeReference<T> typeReference) {
        try {
            return objectMapper.convertValue(value, objectMapper.constructType(typeReference));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Failed to convert JSON payload.", ex);
        }
    }
}
