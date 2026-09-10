package com.akshitanchan.saas.auth;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthMagicLinkRepository extends JpaRepository<AuthMagicLink, String> {

    // atomic single-use + expiry gate: claims the link and returns its owner in one statement,
    // mirroring the python side's update(...).returning(AuthMagicLink.user_id)
    @Query(value = """
            update auth_magic_links
            set used_at = :now
            where token_hash = :hash and used_at is null and expires_at > :now
            returning user_id
            """, nativeQuery = true)
    Optional<UUID> redeem(@Param("hash") String hash, @Param("now") OffsetDateTime now);
}
