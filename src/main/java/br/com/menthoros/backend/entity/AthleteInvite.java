package br.com.menthoros.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Convite de acesso do atleta, emitido pelo coach. Substitui o invite-user do Keycloak
 * Organizations: o token carrega a intenção completa ("este convite é PARA este atleta"),
 * tornando o vínculo {@code Usuario <-> Atleta} determinístico — o match por e-mail no primeiro
 * login (que falhou no incidente de 2026-09-04) vira fallback.
 *
 * <p>Guarda apenas o <strong>hash</strong> do token. Estado derivado das datas, sem enum: aberto,
 * expirado, invalidado (reenvio gerou outro), em provisionamento ({@code claimedAt}) ou aceito
 * (terminal). {@code claimedAt} é o claim atômico do aceite público: quem o grava primeiro
 * provisiona; a compensação o zera em caso de falha, reabrindo o retry.</p>
 */
@Entity
@Table(name = "tb_athlete_invite")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AthleteInvite {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "atleta_id", nullable = false)
    private UUID atletaId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    /** Snapshot do e-mail do atleta no momento do convite — o cadastro pode ser editado depois. */
    @Column(name = "email_enviado", nullable = false, length = 180)
    private String emailEnviado;

    /** Claim atômico do aceite: preenchido por quem venceu a corrida do provisionamento. */
    @Column(name = "claimed_at")
    private OffsetDateTime claimedAt;

    /** Nulo quando o SMTP falhou: o registro fica para o reenvio invalidar. */
    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @Column(name = "invalidated_at")
    private OffsetDateTime invalidatedAt;

    @Column(name = "accepted_at")
    private OffsetDateTime acceptedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    /** Aberto = ainda pode ser aceito ou invalidado. Expirado continua "aberto" para o índice. */
    public boolean isOpen() {
        return acceptedAt == null && invalidatedAt == null;
    }

    /** Ativo = aberto e dentro da validade: o único estado em que o token abre o cadastro. */
    public boolean isActive(OffsetDateTime now) {
        return isOpen() && expiresAt.isAfter(now);
    }
}
