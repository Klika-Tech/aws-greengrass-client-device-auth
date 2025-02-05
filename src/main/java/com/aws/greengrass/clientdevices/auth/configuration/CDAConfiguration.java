/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.clientdevices.auth.configuration;

import com.aws.greengrass.clientdevices.auth.api.DomainEvents;
import com.aws.greengrass.clientdevices.auth.certificate.events.CAConfigurationChanged;
import com.aws.greengrass.clientdevices.auth.configuration.events.MetricsConfigurationChanged;
import com.aws.greengrass.clientdevices.auth.configuration.events.SecurityConfigurationChanged;
import com.aws.greengrass.config.Topics;
import lombok.Getter;

import java.net.URISyntaxException;
import java.util.List;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.CONFIGURATION_CONFIG_KEY;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.RUNTIME_STORE_NAMESPACE_TOPIC;

/**
 * Factory for the service configuration. Given some Topic it will create the service configuration, validating its
 * values and setting defaults as needed.
 * <p>
 * Certificate Manager Service uses the following topic structure:
 * |---- configuration
 * |    |---- security:
 * |         |---- clientDeviceTrustDurationMinutes: "..."
 * |    |---- performance:
 * |         |---- cloudRequestQueueSize: "..."
 * |         |---- maxConcurrentCloudRequests: "..."
 * |         |---- maxActiveAuthTokens: "..."
 * |    |---- deviceGroups:
 * |         |---- definitions : {}
 * |         |---- policies : {}
 * |    |---- certificateAuthority:
 * |         |---- privateKeyUri: "..."
 * |         |---- certificateUri: "..."
 * |         |---- caType: [...]
 * |    |---- certificates: {}
 * |    |---- metrics:
 * |         |---- disableMetrics: "..."
 * |         |---- aggregatePeriod: "..."
 * |---- runtime
 * |    |---- ca_passphrase: "..."
 * |    |---- certificates:
 * |         |---- authorities: [...]
 * |
 * </p>
 */
public final class CDAConfiguration {

    private final RuntimeConfiguration runtime;
    private final SecurityConfiguration security;
    @Getter
    private final CAConfiguration certificateAuthorityConfiguration;
    private final MetricsConfiguration metricsConfiguration;
    private final DomainEvents domainEvents;

    private CDAConfiguration(DomainEvents domainEvents, RuntimeConfiguration runtime, CAConfiguration ca,
                             SecurityConfiguration security, MetricsConfiguration metricsConfiguration) {
        this.domainEvents = domainEvents;
        this.runtime = runtime;
        this.security = security;
        this.certificateAuthorityConfiguration = ca;
        this.metricsConfiguration = metricsConfiguration;
    }

    /**
     * Creates the CDA (Client Device Auth) Service configuration. And allows it to be available in the context with the
     * updated values
     *
     * @param existingConfig an existing version of the CDAConfiguration
     * @param topics         configuration topics from GG
     * @throws URISyntaxException if invalid URI inside the configuration
     */
    public static CDAConfiguration from(CDAConfiguration existingConfig, Topics topics) throws URISyntaxException {
        Topics runtimeTopics = topics.lookupTopics(RUNTIME_STORE_NAMESPACE_TOPIC);
        Topics serviceConfiguration = topics.lookupTopics(CONFIGURATION_CONFIG_KEY);

        DomainEvents domainEvents = topics.getContext().get(DomainEvents.class);

        CDAConfiguration newConfig = new CDAConfiguration(domainEvents, RuntimeConfiguration.from(runtimeTopics),
                CAConfiguration.from(serviceConfiguration), SecurityConfiguration.from(serviceConfiguration),
                MetricsConfiguration.from(serviceConfiguration));

        newConfig.triggerChanges(newConfig, existingConfig);

        return newConfig;
    }

    /**
     * Creates the CDA (Client Device Auth) Service configuration.
     *
     * @param topics configuration topics from GG
     * @throws URISyntaxException if invalid URI inside the configuration
     */
    public static CDAConfiguration from(Topics topics) throws URISyntaxException {
        return from(null, topics);
    }

    private void triggerChanges(CDAConfiguration current, CDAConfiguration prev) {
        if (hasCAConfigurationChanged(prev)) {
            domainEvents.emit(new CAConfigurationChanged(current.getCertificateAuthorityConfiguration()));
        }
        if (hasSecurityConfigurationChanged(prev)) {
            domainEvents.emit(new SecurityConfigurationChanged(current.security));
        }
        if (hasMetricsConfigurationChanged(prev)) {
            domainEvents.emit(new MetricsConfigurationChanged(current.metricsConfiguration));
        }
    }

    public void updateCACertificates(List<String> caCertificates) {
        runtime.updateCACertificates(caCertificates);
    }

    /**
     * Verifies if the configuration for the certificateAuthority has changed, given a previous configuration.
     *
     * @param config CDAConfiguration
     */
    private boolean hasCAConfigurationChanged(CDAConfiguration config) {
        if (config == null) {
            return true;
        }

        CAConfiguration caConfiguration = config.getCertificateAuthorityConfiguration();
        return certificateAuthorityConfiguration.hasChanged(caConfiguration);
    }

    private boolean hasSecurityConfigurationChanged(CDAConfiguration config) {
        if (config == null) {
            return true;
        }
        return security.hasChanged(config.security);
    }

    private boolean hasMetricsConfigurationChanged(CDAConfiguration config) {
        if (config == null) {
            return true;
        }
        return metricsConfiguration.hasChanged(config.metricsConfiguration);
    }
}
