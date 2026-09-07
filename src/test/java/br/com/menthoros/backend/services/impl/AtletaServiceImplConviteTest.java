package br.com.menthoros.backend.services.impl;

import br.com.menthoros.backend.services.AthleteInviteService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;

/**
 * O gerarConvite delega integralmente ao {@link AthleteInviteService} (change
 * add-athlete-invite-token-link). As regras do convite — atleta do tenant, e-mail obrigatório,
 * corrida de emissão, falha de SMTP — são cobertas em {@code AthleteInviteServiceImplInviteTest}.
 */
@ExtendWith(MockitoExtension.class)
class AtletaServiceImplConviteTest {

    @Mock private br.com.menthoros.backend.repository.AtletaRepository atletaRepository;
    @Mock private br.com.menthoros.backend.repository.AssessoriaRepository assessoriaRepository;
    @Mock private br.com.menthoros.backend.mapper.AtletaMapper atletaMapper;
    @Mock private br.com.menthoros.backend.repository.PlanoMetadadosRepository planoMetadadosRepository;
    @Mock private br.com.menthoros.backend.services.TsbService tsbService;
    @Mock private AthleteInviteService athleteInviteService;

    @InjectMocks
    private AtletaServiceImpl atletaService;

    @Test
    @DisplayName("delega a emissão do convite ao AthleteInviteService")
    void delegaAoAthleteInviteService() {
        UUID atletaId = UUID.randomUUID();

        atletaService.gerarConvite(atletaId);

        verify(athleteInviteService).invite(atletaId);
    }
}
