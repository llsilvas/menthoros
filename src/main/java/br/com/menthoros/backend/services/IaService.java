package br.com.menthoros.backend.services;

import br.com.menthoros.backend.dto.DecisaoProgressao;
import br.com.menthoros.backend.dto.llm.PlanoSemanalLlmDto;
import br.com.menthoros.backend.dto.output.AtletaOutputDto;
import br.com.menthoros.backend.dto.output.PlanoSemanalOutputDto;
import br.com.menthoros.backend.dto.output.PlanoTreinoOutputDto;
import br.com.menthoros.backend.dto.output.TreinoRealizadoOutputDto;
import br.com.menthoros.backend.entity.Atleta;
import br.com.menthoros.backend.entity.PlanoMetaDados;
import br.com.menthoros.backend.entity.Prova;
import br.com.menthoros.backend.entity.RevisaoSemanal;
import br.com.menthoros.backend.enums.ModoGeracaoPlano;
import br.com.menthoros.backend.domain.planner.WeekPlanSkeleton;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface IaService {

    PlanoSemanalLlmDto gerarPlanoSemanal(AtletaOutputDto atletaOutputDto, List<TreinoRealizadoOutputDto> treinoRealizadoOutputDtoList, PlanoSemanalOutputDto planoSemanalOutputDto);

    /**
     * @param revisaoConsumida revisão da semana anterior já resolvida pelo chamador — resolver aqui
     *        dentro faria o LLM ver uma revisão e o plano gravar outra (leituras não-atômicas).
     * @param inicioSemana semana do plano, resolvida pelo mesmo chamador. Derivá-la aqui de
     *        {@code LocalDate.now()} divergiria de {@code calcularSemanaInicio} quando o atleta já
     *        tem plano futuro: o prompt falaria de uma semana e o plano seria salvo em outra.
     * @param skeleton estrutura prescritiva do planner (planner-engine-enforcement §3); {@code null}
     *        quando {@code planner-engine.enabled=false} — nesse caso o prompt é idêntico ao legado.
     */
    PlanoSemanalLlmDto geraPlanoSemanalAvancado(Atleta atleta, PlanoMetaDados metaDados, Prova prova, ModoGeracaoPlano modoGeracao, @Nullable DecisaoProgressao decisaoProgressao, @Nullable RevisaoSemanal revisaoConsumida, LocalDate inicioSemana, @Nullable WeekPlanSkeleton skeleton);

    Map<Long, PlanoTreinoOutputDto> gerarPlanosEmLote(Map<AtletaOutputDto, List<TreinoRealizadoOutputDto>> atletaDtoListMap);
}
