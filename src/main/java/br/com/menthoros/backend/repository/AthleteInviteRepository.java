package br.com.menthoros.backend.repository;

import br.com.menthoros.backend.entity.AthleteInvite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AthleteInviteRepository extends JpaRepository<AthleteInvite, UUID> {

    Optional<AthleteInvite> findByTokenHash(String tokenHash);

    /**
     * O convite "aberto" do atleta — nem aceito nem invalidado — <strong>inclusive expirado ou sem
     * {@code sent_at}</strong>. É o que o reenvio precisa invalidar antes de inserir: o índice
     * parcial único não olha {@code expires_at}. No máximo um por atleta, garantido pelo índice.
     *
     * <p>Tenant-aware: NO por design — o {@code atletaId} chega validado contra o tenant pelo
     * chamador ({@code findByIdAndTenantId} antes desta consulta); não é filtro esquecido.</p>
     */
    @Query("""
            SELECT i FROM AthleteInvite i
            WHERE i.atletaId = :atletaId
              AND i.acceptedAt IS NULL
              AND i.invalidatedAt IS NULL
            """)
    Optional<AthleteInvite> findOpenByAtletaId(@Param("atletaId") UUID atletaId);

    /**
     * Claim atômico do aceite: só o primeiro UPDATE vence (rowcount 1); concorrentes recebem 0 e
     * respondem 410. A compensação do provisionamento usa {@link #liberarClaim} para reabrir.
     *
     * <p><strong>Idempotent:</strong> NO — por design: o claim é o mecanismo de exclusão mútua.
     * <p><strong>Side Effects:</strong> Database update.
     * <p><strong>Tenant-aware:</strong> NO — o token é o segredo; o tenant sai do próprio convite.
     */
    @Transactional // o serviço que chama roda sem transação (chamadas externas); o claim é atômico sozinho
    @Modifying
    @Query("""
            UPDATE AthleteInvite i
            SET i.claimedAt = :now
            WHERE i.id = :id
              AND i.claimedAt IS NULL
              AND i.acceptedAt IS NULL
              AND i.invalidatedAt IS NULL
            """)
    int claim(@Param("id") UUID id, @Param("now") OffsetDateTime now);

    /** Reabre o convite após falha do provisionamento (compensação do aceite). */
    @Transactional
    @Modifying
    @Query("UPDATE AthleteInvite i SET i.claimedAt = NULL WHERE i.id = :id AND i.acceptedAt IS NULL")
    int liberarClaim(@Param("id") UUID id);
}
