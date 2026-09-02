package com.optimumpathinc.nexus.mcp.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.optimumpathinc.nexus.mcp.config.SidecarProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Task 7.16 — static registry validation and BLSS-first configuration. */
class BackendRegistryTest {

    @Test
    void blssIsTheFirstConfiguredBackend() {
        BackendRegistry registry = GatewayTestSupport.registry("blss", "http://localhost:8091/mcp");

        assertThat(registry.backends()).hasSize(1);
        assertThat(registry.backends().get(0).prefix()).isEqualTo("blss");
        assertThat(registry.byPrefix("blss")).isNotNull();
    }

    @Test
    void unconfiguredPrefixResolvesToNothing() {
        BackendRegistry registry = GatewayTestSupport.registry("blss", "http://localhost:8091/mcp");

        // Superset is deliberately absent until its authentication contract is confirmed (7.21).
        assertThat(registry.byPrefix("superset")).isNull();
    }

    @Test
    void duplicatePrefixFailsStartup() {
        assertThatThrownBy(() -> GatewayTestSupport.registry(
                "blss", "http://a.local/mcp",
                "blss", "http://b.local/mcp"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate prefix");
    }

    @Test
    void prefixesAreCaseSensitiveSoTheyDoNotCollide() {
        BackendRegistry registry = GatewayTestSupport.registry(
                "blss", "http://a.local/mcp",
                "BLSS", "http://b.local/mcp");

        assertThat(registry.backends()).hasSize(2);
        assertThat(registry.byPrefix("blss").baseUrl()).hasToString("http://a.local/mcp");
        assertThat(registry.byPrefix("BLSS").baseUrl()).hasToString("http://b.local/mcp");
    }

    @Test
    void blankPrefixFailsStartup() {
        assertThatThrownBy(() -> GatewayTestSupport.registry("", "http://a.local/mcp"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prefix is required");
    }

    @Test
    void prefixOutsideTheMcpToolNameGrammarFailsStartup() {
        assertThatThrownBy(() -> GatewayTestSupport.registry("bl.ss", "http://a.local/mcp"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[a-zA-Z0-9_-]+");
    }

    @Test
    void prefixContainingTheSeparatorFailsStartup() {
        assertThatThrownBy(() -> GatewayTestSupport.registry("bl__ss", "http://a.local/mcp"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not contain '__'");
    }

    @Test
    void nonAbsoluteUrlFailsStartup() {
        assertThatThrownBy(() -> GatewayTestSupport.registry("blss", "/mcp"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be absolute");
    }

    @Test
    void missingUrlFailsStartup() {
        assertThatThrownBy(() -> new BackendRegistry(List.of(new SidecarProperties.Backend("blss", null))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("url is required");
    }

    @Test
    void validRegistryStartsWithoutContactingAnyBackend() {
        // Construction performs no I/O, so an unreachable backend cannot fail startup.
        BackendRegistry registry = GatewayTestSupport.registry("blss", "http://unreachable.invalid:9/mcp");

        assertThat(registry.backends()).hasSize(1);
    }
}
