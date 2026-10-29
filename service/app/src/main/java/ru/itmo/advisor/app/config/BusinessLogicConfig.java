package ru.itmo.advisor.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.itmo.advisor.analysis.evaluation.TimeOverlapEvaluator;
import ru.itmo.advisor.analysis.explain.TemplateExplanationWriter;
import ru.itmo.advisor.analysis.graph.TagServiceGraphProvider;
import ru.itmo.advisor.analysis.logs.NoLogEvidenceExtractor;
import ru.itmo.advisor.analysis.metrics.BaselineDeviationDetector;
import ru.itmo.advisor.analysis.ranking.EarliestDeviationRanker;
import ru.itmo.advisor.analysis.ranking.NoCauseSymptomPolicy;
import ru.itmo.advisor.analysis.stream.NoopValueStreamProcessor;
import ru.itmo.advisor.domain.port.CauseSymptomPolicy;
import ru.itmo.advisor.domain.port.ExplanationWriter;
import ru.itmo.advisor.domain.port.LlmClient;
import ru.itmo.advisor.domain.port.LogEvidenceExtractor;
import ru.itmo.advisor.domain.port.MetricDeviationDetector;
import ru.itmo.advisor.domain.port.QualityEvaluator;
import ru.itmo.advisor.domain.port.ServiceGraphProvider;
import ru.itmo.advisor.domain.port.SuspectRanker;
import ru.itmo.advisor.domain.port.ValueStreamProcessor;
import ru.itmo.advisor.domain.port.ZabbixWriter;

/**
 * ЕДИНСТВЕННОЕ место, где выбираются реализации бизнес-логики.
 * Написал свою реализацию в своём модуле analysis-* — поменяй одну строку здесь.
 */
@Configuration
public class BusinessLogicConfig {

    @Bean
    ServiceGraphProvider serviceGraphProvider() {
        return new TagServiceGraphProvider();
    }

    @Bean
    MetricDeviationDetector metricDeviationDetector() {
        return new BaselineDeviationDetector();
    }

    @Bean
    LogEvidenceExtractor logEvidenceExtractor() {
        return new NoLogEvidenceExtractor();
    }

    @Bean
    SuspectRanker suspectRanker() {
        return new EarliestDeviationRanker();
    }

    @Bean
    CauseSymptomPolicy causeSymptomPolicy() {
        return new NoCauseSymptomPolicy();
    }

    @Bean
    ExplanationWriter explanationWriter(LlmClient llm) {
        return new TemplateExplanationWriter(llm);
    }

    @Bean
    QualityEvaluator qualityEvaluator() {
        return new TimeOverlapEvaluator();
    }

    @Bean
    ValueStreamProcessor valueStreamProcessor(ZabbixWriter zabbix) {
        return new NoopValueStreamProcessor();
    }
}
