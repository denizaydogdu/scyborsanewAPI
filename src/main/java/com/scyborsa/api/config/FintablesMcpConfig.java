package com.scyborsa.api.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Fintables MCP (Model Context Protocol) sunucusu için yapılandırma sınıfı.
 *
 * <p>OAuth 2.0 Authorization Code + PKCE (S256) akışı ile kimlik doğrulama yapar.
 * Public client olduğu için client_secret gerekmez.</p>
 *
 * <p>{@code fintables.mcp} prefix'i altındaki property'leri okur:</p>
 * <ul>
 *   <li>{@code fintables.mcp.client-id} - OAuth 2.0 public client ID</li>
 *   <li>{@code fintables.mcp.authorization-endpoint} - OAuth 2.0 authorize URL</li>
 *   <li>{@code fintables.mcp.token-endpoint} - OAuth 2.0 token URL</li>
 *   <li>{@code fintables.mcp.mcp-url} - MCP JSON-RPC endpoint URL</li>
 *   <li>{@code fintables.mcp.callback-url} - OAuth 2.0 redirect URI (callback)</li>
 * </ul>
 *
 * @see com.scyborsa.api.service.client.FintablesMcpClient
 * @see com.scyborsa.api.controller.FintablesMcpAuthController
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "fintables.mcp")
public class FintablesMcpConfig {

    /** OAuth 2.0 public client ID. */
    private String clientId = "fa8f9dc4-f2d6-4da8-b750-47d529e4b3a4";

    /** OAuth 2.0 authorization endpoint URL. */
    private String authorizationEndpoint = "https://evo.fintables.com/authorize";

    /** OAuth 2.0 token endpoint URL. */
    private String tokenEndpoint = "https://evo.fintables.com/token";

    /** Fintables MCP JSON-RPC endpoint URL. */
    private String mcpUrl = "https://evo.fintables.com/mcp";

    /** OAuth 2.0 redirect URI (callback URL). */
    private String callbackUrl = "http://localhost:8081/api/v1/fintables/callback";
}
