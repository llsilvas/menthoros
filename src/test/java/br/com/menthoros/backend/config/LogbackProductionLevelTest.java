package br.com.menthoros.backend.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guarda de regressão do incidente de 2026-09-04: loggers de security/multitenancy em DEBUG
 * incondicional inundaram os logs de produção (4+ linhas por requisição) e estouraram o rate
 * limit de 500 logs/s do Railway durante o esgotamento do pool.
 *
 * O teste é estrutural sobre o logback-spring.xml, e não um @SpringBootTest com profile cloud,
 * de propósito: o profile cloud derruba o startup sem SMTP_HOST (por design, ver
 * application-cloud.yml), e a inicialização do Logback é por JVM — num JVM compartilhado entre
 * suítes a asserção por contexto ficaria dependente da ordem de execução.
 */
class LogbackProductionLevelTest {

    private static Document logbackXml;

    @BeforeAll
    static void carregaLogback() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        try (InputStream in = LogbackProductionLevelTest.class
                .getResourceAsStream("/logback-spring.xml")) {
            logbackXml = factory.newDocumentBuilder().parse(in);
        }
    }

    @Test
    @DisplayName("nenhum logger em DEBUG fora do springProfile dev")
    void nenhumDebugIncondicional() {
        List<String> violacoes = new ArrayList<>();
        NodeList loggers = logbackXml.getElementsByTagName("logger");
        for (int i = 0; i < loggers.getLength(); i++) {
            Element logger = (Element) loggers.item(i);
            if (!"DEBUG".equalsIgnoreCase(logger.getAttribute("level"))) {
                continue;
            }
            if (!dentroDeSpringProfile(logger, "dev")) {
                violacoes.add(logger.getAttribute("name"));
            }
        }
        assertThat(violacoes)
                .as("loggers em DEBUG fora de <springProfile name=\"dev\"> valem para produção "
                        + "(profile cloud) — foi o que inundou os logs no incidente de 2026-09-04")
                .isEmpty();
    }

    @Test
    @DisplayName("root em DEBUG só dentro do springProfile dev")
    void rootDebugSoNoDev() {
        NodeList roots = logbackXml.getElementsByTagName("root");
        for (int i = 0; i < roots.getLength(); i++) {
            Element root = (Element) roots.item(i);
            if ("DEBUG".equalsIgnoreCase(root.getAttribute("level"))) {
                assertThat(dentroDeSpringProfile(root, "dev"))
                        .as("root DEBUG fora do profile dev valeria em produção")
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("não existe bloco springProfile 'prod' — o profile real de produção é 'cloud'")
    void semBlocoProdMorto() {
        NodeList profiles = logbackXml.getElementsByTagName("springProfile");
        for (int i = 0; i < profiles.getLength(); i++) {
            Element profile = (Element) profiles.item(i);
            assertThat(profile.getAttribute("name"))
                    .as("bloco springProfile que nunca ativa (profile de produção é 'cloud')")
                    .isNotEqualTo("prod");
        }
    }

    @Test
    @DisplayName("diagnóstico de dev preservado: security/multitenancy em DEBUG dentro do profile dev")
    void devMantemDebugDeDiagnostico() {
        List<String> nomesEmDebugNoDev = new ArrayList<>();
        NodeList loggers = logbackXml.getElementsByTagName("logger");
        for (int i = 0; i < loggers.getLength(); i++) {
            Element logger = (Element) loggers.item(i);
            if ("DEBUG".equalsIgnoreCase(logger.getAttribute("level"))
                    && dentroDeSpringProfile(logger, "dev")) {
                nomesEmDebugNoDev.add(logger.getAttribute("name"));
            }
        }
        assertThat(nomesEmDebugNoDev).contains(
                "br.com.menthoros.backend.security",
                "br.com.menthoros.backend.multitenancy");
    }

    private static boolean dentroDeSpringProfile(Element element, String profile) {
        for (Node pai = element.getParentNode(); pai != null; pai = pai.getParentNode()) {
            if (pai instanceof Element el
                    && "springProfile".equals(el.getTagName())
                    && profile.equals(el.getAttribute("name"))) {
                return true;
            }
        }
        return false;
    }
}
