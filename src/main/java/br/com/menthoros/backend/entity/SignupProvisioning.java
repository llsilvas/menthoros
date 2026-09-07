package br.com.menthoros.backend.entity;

import br.com.menthoros.backend.enums.ProvisioningOrigin;
import br.com.menthoros.backend.enums.SignupProvisioningStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Rastro de uma tentativa de auto-cadastro de assessoria.
 *
 * <p>Existe separada de {@link Assessoria} porque o cadastro começa <strong>antes</strong> de a
 * assessoria existir e precisa sobreviver ao caso em que ela nunca chega a ser criada — inclusive
 * quando a compensação a apaga. Por isso {@code assessoriaId} é um UUID solto, e não um
 * {@code @ManyToOne}: a linha referenciada pode ter sido removida de propósito.</p>
 *
 * <p><strong>Nunca</strong> guarda senha nem token.</p>
 */
@Entity
@Table(name = "tb_signup_provisioning")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SignupProvisioning {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    /** Hash do payload SEM a senha — distingue reenvio idêntico de chave reusada com outro corpo. */
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    /** Corpo devolvido na primeira execução, replicado no reenvio idempotente. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result")
    private String result;

    @Column(name = "email", nullable = false, length = 100)
    private String email;

    @Column(name = "slug", nullable = false, length = 100)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private SignupProvisioningStatus status;

    @Column(name = "assessoria_id")
    private UUID assessoriaId;

    @Column(name = "keycloak_organization_id", length = 64)
    private String keycloakOrganizationId;

    @Column(name = "keycloak_user_id", length = 64)
    private String keycloakUserId;

    @Column(name = "correlation_id", nullable = false, length = 64)
    private String correlationId;

    @Column(name = "error_detail")
    private String errorDetail;

    /** De onde veio o rastro: cadastro público ou aceite de convite de fundadora. */
    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "origin", nullable = false, length = 30)
    private ProvisioningOrigin origin = ProvisioningOrigin.PUBLIC_SIGNUP;

    /** Convite que originou a tentativa; sustenta a chave de idempotência por tentativa. */
    @Column(name = "invite_id")
    private UUID inviteId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
