package common.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class RolesUtils {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, List<String>>> TYPE_REF = new TypeReference<>() {};

    private RolesUtils() {}

    public static String serialize(Map<String, List<String>> roles) {
        if (roles == null || roles.isEmpty()) return "{}";
        try {
            return OBJECT_MAPPER.writeValueAsString(roles);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize roles", e);
        }
    }

    public static Map<String, List<String>> deserialize(String roles) {
        if (roles == null || roles.isBlank()) return Collections.emptyMap();
        try {
            return OBJECT_MAPPER.readValue(roles, TYPE_REF);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize roles", e);
        }
    }
}