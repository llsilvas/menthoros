package br.com.menthoros.backend.repository;

import br.com.menthoros.backend.AbstractIntegrationTest;
import br.com.menthoros.backend.entity.SignupProvisioning;
import br.com.menthoros.backend.enums.ProvisioningOrigin;
import br.com.menthoros.backend.enums.SignupProvisioningStatus;
import br.com.menthoros.backend.security.InviteToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regressão do ensaio de 2026-09-05: a chave de idempotência do modo convite é
 * {@code "<token_hash>:<n>"} — 64 chars de SHA-256 + sufixo — e a coluna nasceu varchar(64).
 * TODO aceite de convite de fundadora estourava o insert (22001 → 409 enganoso), e nenhum teste
 * persistia a chave no formato real contra o Postgres de verdade. Este IT fecha esse gap: o
 * formato de produção precisa caber na coluna, com folga para o sufixo crescer.
 */
class SignupProvisioningKeyLengthIT extends AbstractIntegrationTest {

    @Autowired
    private SignupProvisioningRepository repository;

    @Test
    @DisplayName("a chave do modo convite (hash de 64 + ':<n>') cabe na coluna")
    void chaveDoModoConviteCabeNaColuna() {
        // Mesmo formato de CoachSignupServiceImpl.chavePorTentativa, com sufixo de 3 dígitos
        String chave = InviteToken.generate().hash() + ":100";
        assertThat(chave.length()).isGreaterThan(64); // era exatamente o que não cabia

        SignupProvisioning salvo = repository.save(SignupProvisioning.builder()
                .idempotencyKey(chave)
                .requestHash(InviteToken.generate().hash())
                .email("regressao-" + UUID.randomUUID() + "@it.test")
                .slug("regressao-" + UUID.randomUUID())
                .status(SignupProvisioningStatus.STARTED)
                .correlationId(UUID.randomUUID().toString())
                .origin(ProvisioningOrigin.FOUNDING_INVITE)
                .build());

        assertThat(repository.findByIdempotencyKey(chave)).isPresent();
        repository.delete(salvo);
    }
}
