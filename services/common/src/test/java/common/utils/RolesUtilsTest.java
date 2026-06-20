package common.utils;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RolesUtilsTest {

    @Test
    void serialize_returnsEmptyJson_forNull() {
        assertThat(RolesUtils.serialize(null)).isEqualTo("{}");
    }

    @Test
    void serialize_returnsEmptyJson_forEmptyMap() {
        assertThat(RolesUtils.serialize(Collections.emptyMap())).isEqualTo("{}");
    }

    @Test
    void serialize_producesValidJson_forSingleEntry() {
        Map<String, List<String>> roles = Map.of("svc-1", List.of("ROLE_A", "ROLE_B"));
        String json = RolesUtils.serialize(roles);
        assertThat(json).contains("\"svc-1\"");
        assertThat(json).contains("\"ROLE_A\"");
        assertThat(json).contains("\"ROLE_B\"");
    }

    @Test
    void serialize_producesValidJson_forMultipleEntries() {
        Map<String, List<String>> roles = Map.of(
                "svc-1", List.of("ROLE_A"),
                "svc-2", List.of("ROLE_B", "ROLE_C")
        );
        String json = RolesUtils.serialize(roles);
        assertThat(json).contains("svc-1").contains("svc-2");
    }

    @Test
    void deserialize_returnsEmptyMap_forNull() {
        assertThat(RolesUtils.deserialize(null)).isEmpty();
    }

    @Test
    void deserialize_returnsEmptyMap_forBlankString() {
        assertThat(RolesUtils.deserialize("   ")).isEmpty();
    }

    @Test
    void deserialize_parsesValidJson() {
        String json = "{\"svc-1\":[\"ROLE_A\",\"ROLE_B\"]}";
        Map<String, List<String>> result = RolesUtils.deserialize(json);
        assertThat(result).containsKey("svc-1");
        assertThat(result.get("svc-1")).containsExactlyInAnyOrder("ROLE_A", "ROLE_B");
    }

    @Test
    void deserialize_throwsRuntimeException_forInvalidJson() {
        assertThatThrownBy(() -> RolesUtils.deserialize("{not valid json"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to deserialize roles");
    }

    @Test
    void privateConstructor_isCallableViaReflection() throws Exception {
        Constructor<RolesUtils> c = RolesUtils.class.getDeclaredConstructor();
        c.setAccessible(true);
        c.newInstance();
    }

    @Test
    void roundtrip_serializeAndDeserialize_preservesData() {
        Map<String, List<String>> original = Map.of(
                "svc-1", List.of("ROLE_A", "ROLE_B"),
                "svc-2", List.of("ROLE_C")
        );
        String json = RolesUtils.serialize(original);
        Map<String, List<String>> restored = RolesUtils.deserialize(json);
        assertThat(restored).containsKey("svc-1");
        assertThat(restored.get("svc-1")).containsExactlyInAnyOrder("ROLE_A", "ROLE_B");
        assertThat(restored.get("svc-2")).containsExactly("ROLE_C");
    }
}
