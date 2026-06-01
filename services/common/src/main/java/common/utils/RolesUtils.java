package common.utils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class RolesUtils {

    private static final String DELIMITER = ",";

    private RolesUtils() {}

    public static String serialize(List<String> roles) {
        if (roles == null || roles.isEmpty()) return "";
        return String.join(DELIMITER, roles);
    }

    public static List<String> deserialize(String roles) {
        if (roles == null || roles.isBlank()) return Collections.emptyList();
        return Arrays.stream(roles.split(DELIMITER))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}