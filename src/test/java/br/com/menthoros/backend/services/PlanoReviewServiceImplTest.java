package br.com.menthoros.backend.services;

import br.com.menthoros.backend.dto.output.PlanoSemanalOutputDto;
import br.com.menthoros.backend.entity.Atleta;
import br.com.menthoros.backend.entity.AthleteBaselineState;
import br.com.menthoros.backend.entity.PlanoSemanal;
import br.com.menthoros.backend.enums.OrigemAprovacao;
import br.com.menthoros.backend.enums.PlanoReviewStatus;
import br.com.menthoros.backend.enums.PlanoStatus;
import br.com.menthoros.backend.events.PlanoAprovadoEvent;
import br.com.menthoros.backend.exception.DomainNotFoundException;
import br.com.menthoros.backend.exception.DomainRuleViolationException;
import br.com.menthoros.backend.mapper.PlanoSemanalMapper;
import br.com.menthoros.backend.repository.AthleteBaselineStateRepository;
import br.com.menthoros.backend.repository.PlanoSemanalRepository;
import br.com.menthoros.backend.services.impl.PlanoReviewServiceImpl;
import br.com.menthoros.backend.services.onboarding.ConfidenceTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Spy;
import br.com.menthoros.backend.entity.RevisaoSemanal;
import br.com.menthoros.backend.entity.TreinoPlanejado;
import br.com.menthoros.backend.enums.ConsumedReviewOutcome;
import br.com.menthoros.backend.enums.FocusSource;
import org.mockito.Mock;
import br.com.menthoros.backend.services.helper.ConsumedReviewOutcomeResolver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlanoReviewServiceImplTest {

    @Mock private PlanoSemanalRepository planoSemanalRepository;
    @Mock private PlanoSemanalMapper planoSemanalMapper;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AthleteBaselineStateRepository athleteBaselineStateRepository;
    // Reais, não mocks: o resolver é lógica pura e o registry em memória deixa a métrica
    // observável — mockar aqui esconderia o desfecho em vez de exercitá-lo.
    @Spy private ConsumedReviewOutcomeResolver outcomeResolver = new ConsumedReviewOutcomeResolver();
    @Spy private MeterRegistry meterRegistry = new SimpleMeterRegistry();
    @InjectMocks private PlanoReviewServiceImpl service;

    private UUID tenantId;
    private UUID planoId;
    private UUID atletaId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        planoId = UUID.randomUUID();
        atletaId = UUID.randomUUID();
    }

    // =========================================================================
    // listarPlanosPendentes
    // =========================================================================

    @Nested
    @DisplayName("listarPlanosPendentes")
    class ListarPlanosPendentes {

        @Test
        @DisplayName("retorna lista mapeada de planos AGUARDANDO_REVISAO do tenant")
        void retornaListaMapeada() {
            PlanoSemanal plano = planoAguardando();
            PlanoSemanalOutputDto dto = outputDto(PlanoReviewStatus.AGUARDANDO_REVISAO);

            when(planoSemanalRepository.findByAssessoriaIdAndReviewStatusOrderBySemanaInicioAsc(
                    eq(tenantId), eq(PlanoReviewStatus.AGUARDANDO_REVISAO), any(LocalDate.class)))
                    .thenReturn(List.of(plano));
            when(planoSemanalMapper.toOutputDtoSafe(plano)).thenReturn(dto);

            List<PlanoSemanalOutputDto> resultado = service.listarPlanosPendentes(tenantId);

            assertThat(resultado).containsExactly(dto);
        }

        @Test
        @DisplayName("retorna lista vazia quando não há planos pendentes")
        void retornaVazioSemPendentes() {
            when(planoSemanalRepository.findByAssessoriaIdAndReviewStatusOrderBySemanaInicioAsc(
                    eq(tenantId), eq(PlanoReviewStatus.AGUARDANDO_REVISAO), any(LocalDate.class)))
                    .thenReturn(List.of());

            List<PlanoSemanalOutputDto> resultado = service.listarPlanosPendentes(tenantId);

            assertThat(resultado).isEmpty();
        }

        @Test
        @DisplayName("lança IllegalArgumentException quando tenantId é nulo")
        void lancaExcecaoParaTenantIdNulo() {
            assertThatThrownBy(() -> service.listarPlanosPendentes(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("tenantId");

            verifyNoInteractions(planoSemanalRepository);
        }
    }

    // =========================================================================
    // aprovarPlano
    // =========================================================================

    @Nested
    @DisplayName("aprovarPlano")
    class AprovarPlano {

        @Test
        @DisplayName("plano que consumiu revisão sem ajuste do coach registra NO_ADJUSTMENT (CA8)")
        void registraDesfechoSemAjuste() {
            PlanoSemanal plano = planoAguardando();
            plano.setAtleta(Atleta.builder().id(atletaId).build());
            plano.setConsumedReview(RevisaoSemanal.builder()
                    .id(UUID.randomUUID()).focusSource(FocusSource.TEMPLATE).build());
            plano.setConsumedReviewOutcome(ConsumedReviewOutcome.PENDING);
            plano.setTreinosPlanejados(List.of(new TreinoPlanejado()));

            when(planoSemanalRepository.findByIdAndTenantId(planoId, tenantId))
                    .thenReturn(Optional.of(plano));
            when(planoSemanalRepository.save(any())).thenReturn(plano);
            when(planoSemanalMapper.toOutputDtoSafe(plano))
                    .thenReturn(outputDto(PlanoReviewStatus.APROVADO));

            service.aprovarPlano(planoId, tenantId);

            assertThat(plano.getConsumedReviewOutcome()).isEqualTo(ConsumedReviewOutcome.NO_ADJUSTMENT);
            assertThat(meterRegistry.counter("weekly_review.outcome",
                    "outcome", "NO_ADJUSTMENT", "focus_source", "TEMPLATE").count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("plano sem revisão consumida não recebe desfecho nem métrica")
        void semRevisaoNaoRegistraDesfecho() {
            PlanoSemanal plano = planoAguardando();
            plano.setAtleta(Atleta.builder().id(atletaId).build());
            plano.setConsumedReviewOutcome(ConsumedReviewOutcome.NOT_CONSUMED);

            when(planoSemanalRepository.findByIdAndTenantId(planoId, tenantId))
                    .thenReturn(Optional.of(plano));
            when(planoSemanalRepository.save(any())).thenReturn(plano);
            when(planoSemanalMapper.toOutputDtoSafe(plano))
                    .thenReturn(outputDto(PlanoReviewStatus.APROVADO));

            service.aprovarPlano(planoId, tenantId);

            assertThat(plano.getConsumedReviewOutcome()).isEqualTo(ConsumedReviewOutcome.NOT_CONSUMED);
            assertThat(meterRegistry.find("weekly_review.outcome").counters()).isEmpty();
        }

        @Test
        @DisplayName("happy path: AGUARDANDO_REVISAO → APROVADO; reviewComment zerado; publica PlanoAprovadoEvent")
        void aprovaPlanoPendente() {
            PlanoSemanal plano = planoAguardando();
            plano.setAtleta(Atleta.builder().id(atletaId).build());
            PlanoSemanalOutputDto dto = outputDto(PlanoReviewStatus.APROVADO);

            when(planoSemanalRepository.findByIdAndTenantId(planoId, tenantId))
                    .thenReturn(Optional.of(plano));
            when(planoSemanalRepository.save(any())).thenReturn(plano);
            when(planoSemanalMapper.toOutputDtoSafe(plano)).thenReturn(dto);

            PlanoSemanalOutputDto resultado = service.aprovarPlano(planoId, tenantId);

            assertThat(resultado).isEqualTo(dto);

            ArgumentCaptor<PlanoSemanal> saveCaptor = ArgumentCaptor.forClass(PlanoSemanal.class);
            verify(planoSemanalRepository).save(saveCaptor.capture());
            assertThat(saveCaptor.getValue().getReviewStatus()).isEqualTo(PlanoReviewStatus.APROVADO);
            assertThat(saveCaptor.getValue().getReviewComment()).isNull();
            assertThat(saveCaptor.getValue().getOrigemAprovacao()).isEqualTo(OrigemAprovacao.COACH);

            // Verifica que o evento foi publicado com os dados corretos
            ArgumentCaptor<PlanoAprovadoEvent> eventCaptor = ArgumentCaptor.forClass(PlanoAprovadoEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            PlanoAprovadoEvent evento = eventCaptor.getValue();
            assertThat(evento.planoId()).isEqualTo(planoId);
            assertThat(evento.atletaId()).isEqualTo(atletaId);
            assertThat(evento.tenantId()).isEqualTo(tenantId);
        }

        @Test
        @DisplayName("lança DomainNotFoundException quando plano não existe no tenant")
        void lancaNotFoundQuandoPlanoAusente() {
            when(planoSemanalRepository.findByIdAndTenantId(planoId, tenantId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.aprovarPlano(planoId, tenantId))
                    .isInstanceOf(DomainNotFoundException.class);

            verify(planoSemanalRepository, never()).save(any());
        }

        @Test
        @DisplayName("lança DomainRuleViolationException quando plano já está APROVADO; NÃO publica evento")
        void lancaRuleViolationQuandoJaAprovado() {
            PlanoSemanal plano = planoComStatus(PlanoReviewStatus.APROVADO);
            when(planoSemanalRepository.findByIdAndTenantId(planoId, tenantId))
                    .thenReturn(Optional.of(plano));

            assertThatThrownBy(() -> service.aprovarPlano(planoId, tenantId))
                    .isInstanceOf(DomainRuleViolationException.class)
                    .hasMessageContaining("Aprovado");

            verify(planoSemanalRepository, never()).save(any());
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("lança DomainRuleViolationException quando plano está REJEITADO; NÃO publica evento")
        void lancaRuleViolationQuandoRejeitado() {
            PlanoSemanal plano = planoComStatus(PlanoReviewStatus.REJEITADO);
            when(planoSemanalRepository.findByIdAndTenantId(planoId, tenantId))
                    .thenReturn(Optional.of(plano));

            assertThatThrownBy(() -> service.aprovarPlano(planoId, tenantId))
                    .isInstanceOf(DomainRuleViolationException.class)
                    .hasMessageContaining("Rejeitado");

            verify(planoSemanalRepository, never()).save(any());
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("lança IllegalArgumentException para planoId nulo")
        void lancaExcecaoParaPlanoIdNulo() {
            assertThatThrownBy(() -> service.aprovarPlano(null, tenantId))
                    .isInstanceOf(IllegalArgumentException.class);
            verifyNoInteractions(planoSemanalRepository);
        }

    }

    // =========================================================================
    // aprovarTransicao (athlete-onboarding-baseline, extraído para reuso pelo auto-approve)
    // =========================================================================

    @Nested
    @DisplayName("aprovarTransicao")
    class AprovarTransicao {

        @Test
        @DisplayName("aplica os mesmos 4 efeitos de aprovarPlano: status, comment, save, evento — mais a origem")
        void aplicaOsMesmosEfeitosDeAprovarPlano() {
            PlanoSemanal plano = planoAguardando();
            plano.setAtleta(Atleta.builder().id(atletaId).build());
            when(planoSemanalRepository.save(any())).thenReturn(plano);

            PlanoSemanal resultado = service.aprovarTransicao(plano, tenantId, OrigemAprovacao.COACH);

            assertThat(resultado.getReviewStatus()).isEqualTo(PlanoReviewStatus.APROVADO);
            assertThat(resultado.getReviewComment()).isNull();
            assertThat(resultado.getOrigemAprovacao()).isEqualTo(OrigemAprovacao.COACH);
            verify(planoSemanalRepository).save(plano);

            ArgumentCaptor<PlanoAprovadoEvent> eventCaptor = ArgumentCaptor.forClass(PlanoAprovadoEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue().atletaId()).isEqualTo(atletaId);
            assertThat(eventCaptor.getValue().tenantId()).isEqualTo(tenantId);
        }

        @Test
        @DisplayName("grava AUTO_CONFIANCA_ALTA quando chamado pelo auto-approve")
        void gravaOrigemAutoConfiancaAlta() {
            PlanoSemanal planoNovo = planoComStatus(null); // plano recem-criado, sem reviewStatus ainda
            planoNovo.setAtleta(Atleta.builder().id(atletaId).build());
            when(planoSemanalRepository.save(any())).thenReturn(planoNovo);

            PlanoSemanal resultado = service.aprovarTransicao(planoNovo, tenantId, OrigemAprovacao.AUTO_CONFIANCA_ALTA);

            assertThat(resultado.getReviewStatus()).isEqualTo(PlanoReviewStatus.APROVADO);
            assertThat(resultado.getOrigemAprovacao()).isEqualTo(OrigemAprovacao.AUTO_CONFIANCA_ALTA);
        }

        @Test
        @DisplayName("nao valida transicao — chamador e responsavel (usado pelo auto-approve em plano novo)")
        void naoValidaTransicaoDeOrigem() {
            PlanoSemanal planoNovo = planoComStatus(null); // plano recem-criado, sem reviewStatus ainda
            planoNovo.setAtleta(Atleta.builder().id(atletaId).build());
            when(planoSemanalRepository.save(any())).thenReturn(planoNovo);

            PlanoSemanal resultado = service.aprovarTransicao(planoNovo, tenantId, OrigemAprovacao.AUTO_CONFIANCA_ALTA);

            assertThat(resultado.getReviewStatus()).isEqualTo(PlanoReviewStatus.APROVADO);
        }

        @Test
        @DisplayName("lança IllegalArgumentException quando plano é nulo")
        void lancaExcecaoParaPlanoNulo() {
            assertThatThrownBy(() -> service.aprovarTransicao(null, tenantId, OrigemAprovacao.COACH))
                    .isInstanceOf(IllegalArgumentException.class);
            verifyNoInteractions(planoSemanalRepository, eventPublisher);
        }

        @Test
        @DisplayName("lança IllegalArgumentException quando tenantId é nulo")
        void lancaExcecaoParaTenantIdNulo() {
            PlanoSemanal plano = planoAguardando();
            assertThatThrownBy(() -> service.aprovarTransicao(plano, null, OrigemAprovacao.COACH))
                    .isInstanceOf(IllegalArgumentException.class);
            verifyNoInteractions(planoSemanalRepository, eventPublisher);
        }

        @Test
        @DisplayName("lança IllegalArgumentException quando origem é nula")
        void lancaExcecaoParaOrigemNula() {
            PlanoSemanal plano = planoAguardando();
            assertThatThrownBy(() -> service.aprovarTransicao(plano, tenantId, null))
                    .isInstanceOf(IllegalArgumentException.class);
            verifyNoInteractions(planoSemanalRepository, eventPublisher);
        }
    }

    // =========================================================================
    // rejeitarPlano
    // =========================================================================

    @Nested
    @DisplayName("rejeitarPlano")
    class RejeitarPlano {

        @Test
        @DisplayName("happy path: AGUARDANDO_REVISAO → REJEITADO; reviewComment persistido")
        void rejeitaPlanoPendente() {
            PlanoSemanal plano = planoAguardando();
            String motivo = "Volume excessivo para a fase atual";
            PlanoSemanalOutputDto dto = outputDto(PlanoReviewStatus.REJEITADO);

            when(planoSemanalRepository.findByIdAndTenantId(planoId, tenantId))
                    .thenReturn(Optional.of(plano));
            when(planoSemanalRepository.save(any())).thenReturn(plano);
            when(planoSemanalMapper.toOutputDtoSafe(plano)).thenReturn(dto);

            PlanoSemanalOutputDto resultado = service.rejeitarPlano(planoId, tenantId, motivo);

            assertThat(resultado).isEqualTo(dto);

            ArgumentCaptor<PlanoSemanal> captor = ArgumentCaptor.forClass(PlanoSemanal.class);
            verify(planoSemanalRepository).save(captor.capture());
            assertThat(captor.getValue().getReviewStatus()).isEqualTo(PlanoReviewStatus.REJEITADO);
            assertThat(captor.getValue().getReviewComment()).isEqualTo(motivo);
        }

        @Test
        @DisplayName("lança DomainNotFoundException quando plano não existe no tenant (cross-tenant)")
        void lancaNotFoundParaCrossTenant() {
            when(planoSemanalRepository.findByIdAndTenantId(planoId, tenantId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.rejeitarPlano(planoId, tenantId, "motivo"))
                    .isInstanceOf(DomainNotFoundException.class);

            verify(planoSemanalRepository, never()).save(any());
        }

        @Test
        @DisplayName("lança DomainRuleViolationException quando plano já está REJEITADO")
        void lancaRuleViolationQuandoJaRejeitado() {
            PlanoSemanal plano = planoComStatus(PlanoReviewStatus.REJEITADO);
            when(planoSemanalRepository.findByIdAndTenantId(planoId, tenantId))
                    .thenReturn(Optional.of(plano));

            assertThatThrownBy(() -> service.rejeitarPlano(planoId, tenantId, "motivo"))
                    .isInstanceOf(DomainRuleViolationException.class)
                    .hasMessageContaining("Rejeitado");

            verify(planoSemanalRepository, never()).save(any());
        }

        @Test
        @DisplayName("lança DomainRuleViolationException quando plano está APROVADO")
        void lancaRuleViolationQuandoAprovado() {
            PlanoSemanal plano = planoComStatus(PlanoReviewStatus.APROVADO);
            when(planoSemanalRepository.findByIdAndTenantId(planoId, tenantId))
                    .thenReturn(Optional.of(plano));

            assertThatThrownBy(() -> service.rejeitarPlano(planoId, tenantId, "motivo"))
                    .isInstanceOf(DomainRuleViolationException.class)
                    .hasMessageContaining("Aprovado");

            verify(planoSemanalRepository, never()).save(any());
        }

        @Test
        @DisplayName("lança IllegalArgumentException quando motivo é nulo")
        void lancaExcecaoParaMotivoNulo() {
            assertThatThrownBy(() -> service.rejeitarPlano(planoId, tenantId, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Motivo");
            verifyNoInteractions(planoSemanalRepository);
        }

        @Test
        @DisplayName("lança IllegalArgumentException quando motivo está em branco")
        void lancaExcecaoParaMotivoEmBranco() {
            assertThatThrownBy(() -> service.rejeitarPlano(planoId, tenantId, "   "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Motivo");
            verifyNoInteractions(planoSemanalRepository);
        }
    }

    // =========================================================================
    // listarPlanosPorStatus
    // =========================================================================

    @Nested
    @DisplayName("listarPlanosPorStatus")
    class ListarPlanosPorStatus {

        @Test
        @DisplayName("retorna planos APROVADOS do tenant")
        void retornaAprovados() {
            PlanoSemanal plano = planoComStatus(PlanoReviewStatus.APROVADO);
            PlanoSemanalOutputDto dto = outputDto(PlanoReviewStatus.APROVADO);

            when(planoSemanalRepository.findByAssessoriaIdAndReviewStatusOrderBySemanaInicioAsc(
                    eq(tenantId), eq(PlanoReviewStatus.APROVADO), any(LocalDate.class)))
                    .thenReturn(List.of(plano));
            when(planoSemanalMapper.toOutputDtoSafe(plano)).thenReturn(dto);

            List<PlanoSemanalOutputDto> resultado = service.listarPlanosPorStatus(tenantId, PlanoReviewStatus.APROVADO);

            assertThat(resultado).containsExactly(dto);
            verify(planoSemanalRepository)
                    .findByAssessoriaIdAndReviewStatusOrderBySemanaInicioAsc(
                            eq(tenantId), eq(PlanoReviewStatus.APROVADO), any(LocalDate.class));
        }

        @Test
        @DisplayName("retorna planos REJEITADOS do tenant")
        void retornaRejeitados() {
            PlanoSemanal plano = planoComStatus(PlanoReviewStatus.REJEITADO);
            PlanoSemanalOutputDto dto = outputDto(PlanoReviewStatus.REJEITADO);

            when(planoSemanalRepository.findByAssessoriaIdAndReviewStatusOrderBySemanaInicioAsc(
                    eq(tenantId), eq(PlanoReviewStatus.REJEITADO), any(LocalDate.class)))
                    .thenReturn(List.of(plano));
            when(planoSemanalMapper.toOutputDtoSafe(plano)).thenReturn(dto);

            List<PlanoSemanalOutputDto> resultado = service.listarPlanosPorStatus(tenantId, PlanoReviewStatus.REJEITADO);

            assertThat(resultado).containsExactly(dto);
        }

        @Test
        @DisplayName("retorna lista vazia quando não há planos com o status")
        void retornaVazioSemPlanos() {
            when(planoSemanalRepository.findByAssessoriaIdAndReviewStatusOrderBySemanaInicioAsc(
                    eq(tenantId), eq(PlanoReviewStatus.APROVADO), any(LocalDate.class)))
                    .thenReturn(List.of());

            List<PlanoSemanalOutputDto> resultado = service.listarPlanosPorStatus(tenantId, PlanoReviewStatus.APROVADO);

            assertThat(resultado).isEmpty();
            verifyNoMoreInteractions(planoSemanalMapper);
        }

        @Test
        @DisplayName("lança IllegalArgumentException quando tenantId é nulo")
        void lancaExcecaoParaTenantIdNulo() {
            assertThatThrownBy(() -> service.listarPlanosPorStatus(null, PlanoReviewStatus.APROVADO))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("tenantId");
            verifyNoInteractions(planoSemanalRepository);
        }

        @Test
        @DisplayName("lança IllegalArgumentException quando reviewStatus é nulo")
        void lancaExcecaoParaStatusNulo() {
            assertThatThrownBy(() -> service.listarPlanosPorStatus(tenantId, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("reviewStatus");
            verifyNoInteractions(planoSemanalRepository);
        }

        @Test
        @DisplayName("enriquece o DTO com confidenceTier do baseline de onboarding do atleta (Cenario B/C)")
        void enriqueceComConfidenceTierQuandoAtletaTemSnapshot() {
            PlanoSemanal plano = planoComStatus(PlanoReviewStatus.AGUARDANDO_REVISAO);
            plano.setAtleta(Atleta.builder().id(atletaId).build());
            PlanoSemanalOutputDto dto = outputDto(PlanoReviewStatus.AGUARDANDO_REVISAO);
            AthleteBaselineState snapshot = new AthleteBaselineState();
            snapshot.setConfidenceTier("B");

            when(planoSemanalRepository.findByAssessoriaIdAndReviewStatusOrderBySemanaInicioAsc(
                    eq(tenantId), eq(PlanoReviewStatus.AGUARDANDO_REVISAO), any(LocalDate.class)))
                    .thenReturn(List.of(plano));
            when(planoSemanalMapper.toOutputDtoSafe(plano)).thenReturn(dto);
            when(athleteBaselineStateRepository.findByAtletaIdAndTenantId(atletaId, tenantId))
                    .thenReturn(Optional.of(snapshot));

            List<PlanoSemanalOutputDto> resultado = service.listarPlanosPorStatus(tenantId, PlanoReviewStatus.AGUARDANDO_REVISAO);

            assertThat(resultado).hasSize(1);
            assertThat(resultado.get(0).confidenceTier()).isEqualTo(ConfidenceTier.B);
        }

        @Test
        @DisplayName("confidenceTier fica nulo quando o atleta ainda não tem AthleteBaselineState")
        void confidenceTierNuloSemSnapshot() {
            PlanoSemanal plano = planoComStatus(PlanoReviewStatus.AGUARDANDO_REVISAO);
            plano.setAtleta(Atleta.builder().id(atletaId).build());
            PlanoSemanalOutputDto dto = outputDto(PlanoReviewStatus.AGUARDANDO_REVISAO);

            when(planoSemanalRepository.findByAssessoriaIdAndReviewStatusOrderBySemanaInicioAsc(
                    eq(tenantId), eq(PlanoReviewStatus.AGUARDANDO_REVISAO), any(LocalDate.class)))
                    .thenReturn(List.of(plano));
            when(planoSemanalMapper.toOutputDtoSafe(plano)).thenReturn(dto);
            when(athleteBaselineStateRepository.findByAtletaIdAndTenantId(atletaId, tenantId))
                    .thenReturn(Optional.empty());

            List<PlanoSemanalOutputDto> resultado = service.listarPlanosPorStatus(tenantId, PlanoReviewStatus.AGUARDANDO_REVISAO);

            assertThat(resultado.get(0).confidenceTier()).isNull();
        }

        @Test
        @DisplayName("confidenceTier fica nulo quando o plano não tem atleta carregado")
        void confidenceTierNuloSemAtletaCarregado() {
            PlanoSemanal plano = planoComStatus(PlanoReviewStatus.AGUARDANDO_REVISAO); // sem setAtleta
            PlanoSemanalOutputDto dto = outputDto(PlanoReviewStatus.AGUARDANDO_REVISAO);

            when(planoSemanalRepository.findByAssessoriaIdAndReviewStatusOrderBySemanaInicioAsc(
                    eq(tenantId), eq(PlanoReviewStatus.AGUARDANDO_REVISAO), any(LocalDate.class)))
                    .thenReturn(List.of(plano));
            when(planoSemanalMapper.toOutputDtoSafe(plano)).thenReturn(dto);

            List<PlanoSemanalOutputDto> resultado = service.listarPlanosPorStatus(tenantId, PlanoReviewStatus.AGUARDANDO_REVISAO);

            assertThat(resultado.get(0).confidenceTier()).isNull();
            verifyNoInteractions(athleteBaselineStateRepository);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private PlanoSemanal planoAguardando() {
        return planoComStatus(PlanoReviewStatus.AGUARDANDO_REVISAO);
    }

    private PlanoSemanal planoComStatus(PlanoReviewStatus reviewStatus) {
        return PlanoSemanal.builder()
                .id(planoId)
                .status(PlanoStatus.PLANEJADO)
                .reviewStatus(reviewStatus)
                .semanaInicio(LocalDate.now())
                .semanaFim(LocalDate.now().plusDays(6))
                .build();
    }

    private PlanoSemanalOutputDto outputDto(PlanoReviewStatus reviewStatus) {
        return new PlanoSemanalOutputDto(
                planoId.toString(), LocalDate.now(), LocalDate.now().plusDays(6),
                40.0, 0.0, 40.0, null, null, PlanoStatus.PLANEJADO,
                null, "Semana base", List.of(), reviewStatus, null, null, "Ana Silva", null, null, null, null, null);
    }
}
