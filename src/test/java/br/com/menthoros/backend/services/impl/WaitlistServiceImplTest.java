package br.com.menthoros.backend.services.impl;

import br.com.menthoros.backend.dto.input.WaitlistInputDto;
import br.com.menthoros.backend.entity.Waitlist;
import br.com.menthoros.backend.enums.FaixaAtletas;
import br.com.menthoros.backend.enums.PerfilWaitlist;
import br.com.menthoros.backend.repository.WaitlistRepository;
import br.com.menthoros.backend.services.WaitlistService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WaitlistServiceImplTest {

    @Mock
    private WaitlistRepository waitlistRepository;

    @InjectMocks
    private WaitlistServiceImpl waitlistService;

    @Nested
    @DisplayName("registrar")
    class Registrar {

        @Test
        @DisplayName("persiste novo e-mail e retorna CRIADO")
        void novoEmailRetornaCriado() {
            when(waitlistRepository.existsByEmailNormalized("maria@exemplo.com")).thenReturn(false);
            when(waitlistRepository.saveAndFlush(any(Waitlist.class))).thenAnswer(inv -> inv.getArgument(0));

            WaitlistService.Resultado resultado = waitlistService.registrar(
                    dto("Maria@Exemplo.com", PerfilWaitlist.TREINADOR, FaixaAtletas.DE_11_A_30, null));

            assertThat(resultado).isEqualTo(WaitlistService.Resultado.CRIADO);
            verify(waitlistRepository).saveAndFlush(any(Waitlist.class));
        }

        @Test
        @DisplayName("grava e-mail normalizado (trim + lowercase) preservando o original")
        void normalizaEmail() {
            when(waitlistRepository.existsByEmailNormalized("maria@exemplo.com")).thenReturn(false);
            when(waitlistRepository.saveAndFlush(any(Waitlist.class))).thenAnswer(inv -> inv.getArgument(0));
            ArgumentCaptor<Waitlist> captor = ArgumentCaptor.forClass(Waitlist.class);

            waitlistService.registrar(dto("  Maria@Exemplo.com  ", PerfilWaitlist.TREINADOR, FaixaAtletas.DE_11_A_30, null));

            verify(waitlistRepository).saveAndFlush(captor.capture());
            assertThat(captor.getValue().getEmailNormalized()).isEqualTo("maria@exemplo.com");
            assertThat(captor.getValue().getEmail()).isEqualTo("Maria@Exemplo.com");
        }

        @Test
        @DisplayName("e-mail já existente retorna JA_INSCRITO sem persistir")
        void emailExistenteRetornaJaInscrito() {
            when(waitlistRepository.existsByEmailNormalized("maria@exemplo.com")).thenReturn(true);

            WaitlistService.Resultado resultado = waitlistService.registrar(
                    dto("maria@exemplo.com", PerfilWaitlist.TREINADOR, null, null));

            assertThat(resultado).isEqualTo(WaitlistService.Resultado.JA_INSCRITO);
            verify(waitlistRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("corrida: violação do índice único é convertida em JA_INSCRITO")
        void corridaRetornaJaInscrito() {
            when(waitlistRepository.existsByEmailNormalized("maria@exemplo.com")).thenReturn(false);
            when(waitlistRepository.saveAndFlush(any(Waitlist.class)))
                    .thenThrow(new DataIntegrityViolationException("uk_waitlist_email_normalized"));

            WaitlistService.Resultado resultado = waitlistService.registrar(
                    dto("maria@exemplo.com", PerfilWaitlist.ATLETA, null, null));

            assertThat(resultado).isEqualTo(WaitlistService.Resultado.JA_INSCRITO);
        }

        @Test
        @DisplayName("honeypot preenchido retorna IGNORADO sem persistir nem consultar")
        void honeypotRetornaIgnorado() {
            WaitlistService.Resultado resultado = waitlistService.registrar(
                    dto("bot@exemplo.com", PerfilWaitlist.TREINADOR, null, "http://spam.example"));

            assertThat(resultado).isEqualTo(WaitlistService.Resultado.IGNORADO);
            verify(waitlistRepository, never()).saveAndFlush(any());
            verify(waitlistRepository, never()).existsByEmailNormalized(any());
        }

        @Test
        @DisplayName("faixa de atletas é ignorada quando o perfil é ATLETA")
        void atletaNaoGravaQtdAtletas() {
            when(waitlistRepository.existsByEmailNormalized("joao@exemplo.com")).thenReturn(false);
            when(waitlistRepository.saveAndFlush(any(Waitlist.class))).thenAnswer(inv -> inv.getArgument(0));
            ArgumentCaptor<Waitlist> captor = ArgumentCaptor.forClass(Waitlist.class);

            waitlistService.registrar(dto("joao@exemplo.com", PerfilWaitlist.ATLETA, FaixaAtletas.MAIS_DE_100, null));

            verify(waitlistRepository).saveAndFlush(captor.capture());
            assertThat(captor.getValue().getQtdAtletas()).isNull();
        }

        @Test
        @DisplayName("dto nulo lança IllegalArgumentException")
        void dtoNuloLanca() {
            assertThatThrownBy(() -> waitlistService.registrar(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("nulo");
            verify(waitlistRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("e-mail em branco lança IllegalArgumentException")
        void emailEmBrancoLanca() {
            WaitlistInputDto dto = new WaitlistInputDto("Maria", "   ", null, PerfilWaitlist.ATLETA, null, true, null, null, null, null, null);
            assertThatThrownBy(() -> waitlistService.registrar(dto))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("E-mail");
            verify(waitlistRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("nome em branco lança IllegalArgumentException")
        void nomeEmBrancoLanca() {
            WaitlistInputDto dto = new WaitlistInputDto("  ", "joao@exemplo.com", null, PerfilWaitlist.ATLETA, null, true, null, null, null, null, null);
            assertThatThrownBy(() -> waitlistService.registrar(dto))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Nome");
            verify(waitlistRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("grava os 4 campos UTM quando enviados")
        void gravaUtmQuandoEnviado() {
            when(waitlistRepository.existsByEmailNormalized("maria@exemplo.com")).thenReturn(false);
            when(waitlistRepository.saveAndFlush(any(Waitlist.class))).thenAnswer(inv -> inv.getArgument(0));
            ArgumentCaptor<Waitlist> captor = ArgumentCaptor.forClass(Waitlist.class);

            WaitlistInputDto dto = new WaitlistInputDto("Maria Treinadora", "maria@exemplo.com", null,
                    PerfilWaitlist.TREINADOR, FaixaAtletas.DE_11_A_30, true, null,
                    "instagram", "social", "turma-fundadora", "bio-link");

            waitlistService.registrar(dto);

            verify(waitlistRepository).saveAndFlush(captor.capture());
            Waitlist salvo = captor.getValue();
            assertThat(salvo.getUtmSource()).isEqualTo("instagram");
            assertThat(salvo.getUtmMedium()).isEqualTo("social");
            assertThat(salvo.getUtmCampaign()).isEqualTo("turma-fundadora");
            assertThat(salvo.getUtmContent()).isEqualTo("bio-link");
        }

        @Test
        @DisplayName("ausência de UTM grava as 4 colunas como null (cliente antigo intocado)")
        void semUtmGravaNull() {
            when(waitlistRepository.existsByEmailNormalized("maria@exemplo.com")).thenReturn(false);
            when(waitlistRepository.saveAndFlush(any(Waitlist.class))).thenAnswer(inv -> inv.getArgument(0));
            ArgumentCaptor<Waitlist> captor = ArgumentCaptor.forClass(Waitlist.class);

            waitlistService.registrar(dto("maria@exemplo.com", PerfilWaitlist.TREINADOR, FaixaAtletas.DE_11_A_30, null));

            verify(waitlistRepository).saveAndFlush(captor.capture());
            Waitlist salvo = captor.getValue();
            assertThat(salvo.getUtmSource()).isNull();
            assertThat(salvo.getUtmMedium()).isNull();
            assertThat(salvo.getUtmCampaign()).isNull();
            assertThat(salvo.getUtmContent()).isNull();
        }
    }

    private WaitlistInputDto dto(String email, PerfilWaitlist perfil, FaixaAtletas faixa, String website) {
        return new WaitlistInputDto("Maria Treinadora", email, "+55 11 99999-9999", perfil, faixa, true, website,
                null, null, null, null);
    }
}
