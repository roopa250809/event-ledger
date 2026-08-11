package com.eventledger.gateway.service;

import com.eventledger.gateway.api.EventRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/** Canonicalizes event payloads for reliable duplicate detection. */
@Component
public class PayloadFingerprint {
    private final ObjectMapper canonicalMapper;

    public PayloadFingerprint(ObjectMapper objectMapper) {
        canonicalMapper = objectMapper.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public CanonicalPayload canonicalize(EventRequest request) {
        String currency = request.currency().toUpperCase();
        String metadata = canonicalMetadata(request.metadata());
        String material = String.join("|", request.eventId(), request.accountId(), request.type().name(),
                request.amount().stripTrailingZeros().toPlainString(), currency,
                request.eventTimestamp().toString(), metadata);
        return new CanonicalPayload(currency, metadata, sha256(material));
    }

    public Map<String, Object> readMetadata(String value) {
        try {
            return canonicalMapper.readValue(value, canonicalMapper.getTypeFactory()
                    .constructMapType(Map.class, String.class, Object.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored metadata is not valid JSON", exception);
        }
    }

    private String canonicalMetadata(Map<String, Object> metadata) {
        try {
            return canonicalMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("metadata must contain JSON-compatible values", exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /** Holds normalized payload values and their content hash. */
    public record CanonicalPayload(String currency, String metadataJson, String hash) {
    }
}
