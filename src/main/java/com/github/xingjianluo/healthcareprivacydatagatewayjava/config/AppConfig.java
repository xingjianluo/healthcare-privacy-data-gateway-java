package com.github.xingjianluo.healthcareprivacydatagatewayjava.config;

import com.github.xingjianluo.healthcareprivacydatagatewayjava.repository.LocalDataRepository;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.service.AuditChainService;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.service.AuthorizationPolicyService;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.service.DeidentificationService;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.service.PatientDataAccessService;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.service.PseudonymService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Spring Bean 装配。 */
@Configuration
public class AppConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public LocalDataRepository localDataRepository() {
        return new LocalDataRepository();
    }

    @Bean
    public PseudonymService pseudonymService() {
        return new PseudonymService();
    }

    @Bean
    public DeidentificationService deidentificationService(PseudonymService pseudonymService) {
        return new DeidentificationService(pseudonymService);
    }

    @Bean
    public AuthorizationPolicyService authorizationPolicyService(Clock clock) {
        return new AuthorizationPolicyService(clock);
    }

    @Bean
    public AuditChainService auditChainService(Clock clock) {
        return new AuditChainService(clock);
    }

    @Bean
    public PatientDataAccessService patientDataAccessService(
            LocalDataRepository repository,
            AuthorizationPolicyService authorizationPolicyService,
            DeidentificationService deidentificationService,
            AuditChainService auditChainService) {
        return new PatientDataAccessService(repository, authorizationPolicyService,
                deidentificationService, auditChainService);
    }
}
