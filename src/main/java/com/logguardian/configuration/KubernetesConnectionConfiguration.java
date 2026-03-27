package com.logguardian.configuration;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
public class KubernetesConnectionConfiguration {

    @Bean
    @Lazy
    public KubernetesClient kubernetesClient() {
        return new KubernetesClientBuilder().build();
    }
}
