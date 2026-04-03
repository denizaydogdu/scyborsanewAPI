package com.scyborsa.api.repository;

import com.scyborsa.api.model.FintablesMcpToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Fintables MCP token veritabanı erişim katmanı.
 *
 * @see com.scyborsa.api.model.FintablesMcpToken
 * @see com.scyborsa.api.service.client.FintablesMcpTokenStore
 */
public interface FintablesMcpTokenRepository extends JpaRepository<FintablesMcpToken, Long> {

    /**
     * Token anahtarına göre token kaydını bulur.
     *
     * @param tokenKey token anahtarı (örn: "MCP_ACCESS_TOKEN")
     * @return token kaydi veya empty
     */
    Optional<FintablesMcpToken> findByTokenKey(String tokenKey);

    /**
     * Token anahtarına göre token kaydını siler.
     *
     * @param tokenKey token anahtari (orn: "MCP_ACCESS_TOKEN")
     */
    @Transactional
    void deleteByTokenKey(String tokenKey);
}
