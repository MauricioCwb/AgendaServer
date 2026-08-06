package br.com.mauricio.agendaserver;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "agenda.scheduling.enabled", havingValue = "true", matchIfMissing = true)
final class AgendaSchedulingConfiguration {
}
