/*
 * Copyright 2022-2024 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sleeper.systemtest.dsl.instance;

import sleeper.configuration.deploy.DeployInstanceConfiguration;
import sleeper.configuration.properties.instance.InstanceProperties;

import java.util.List;
import java.util.function.Supplier;

import static sleeper.configuration.properties.instance.IngestProperty.INGEST_SOURCE_BUCKET;
import static sleeper.configuration.properties.instance.IngestProperty.INGEST_SOURCE_ROLE;

public class SystemTestInstanceConfiguration {
    private final String shortName;
    private final Supplier<DeployInstanceConfiguration> deployConfig;
    private final boolean useSystemTestIngestSourceBucket;

    private SystemTestInstanceConfiguration(Builder builder) {
        shortName = builder.shortName;
        deployConfig = builder.deployConfig;
        useSystemTestIngestSourceBucket = builder.useSystemTestIngestSourceBucket;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static SystemTestInstanceConfiguration usingSystemTestDefaults(
            String shortName, Supplier<DeployInstanceConfiguration> deployConfig) {
        return builder().shortName(shortName).deployConfig(deployConfig).build();
    }

    public static SystemTestInstanceConfiguration noSourceBucket(
            String shortName, Supplier<DeployInstanceConfiguration> deployConfig) {
        return builder().shortName(shortName).deployConfig(deployConfig)
                .useSystemTestIngestSourceBucket(false).build();
    }

    public DeployInstanceConfiguration buildDeployConfig(
            SystemTestParameters parameters, DeployedSystemTestResources systemTest) {
        DeployInstanceConfiguration configuration = buildDeployConfig(parameters);
        InstanceProperties properties = configuration.getInstanceProperties();
        if (shouldUseSystemTestIngestSourceBucket()) {
            properties.set(INGEST_SOURCE_BUCKET, systemTest.getSystemTestBucketName());
        }

        String systemTestClusterRole = systemTest.getSystemTestWriterRoleName();
        if (systemTestClusterRole != null) {
            properties.addToList(INGEST_SOURCE_ROLE, List.of(systemTestClusterRole));
        }
        return configuration;
    }

    public DeployInstanceConfiguration buildDeployConfig(SystemTestParameters parameters) {
        DeployInstanceConfiguration configuration = deployConfig.get();
        parameters.setRequiredProperties(configuration);
        return configuration;
    }

    public String getShortName() {
        return shortName;
    }

    public boolean shouldUseSystemTestIngestSourceBucket() {
        return useSystemTestIngestSourceBucket;
    }

    public static final class Builder {
        private Supplier<DeployInstanceConfiguration> deployConfig;
        private boolean useSystemTestIngestSourceBucket = true;
        private String shortName;

        private Builder() {
        }

        public Builder shortName(String shortName) {
            this.shortName = shortName;
            return this;
        }

        public Builder deployConfig(Supplier<DeployInstanceConfiguration> deployConfig) {
            this.deployConfig = deployConfig;
            return this;
        }

        public Builder useSystemTestIngestSourceBucket(boolean useSystemTestIngestSourceBucket) {
            this.useSystemTestIngestSourceBucket = useSystemTestIngestSourceBucket;
            return this;
        }

        public SystemTestInstanceConfiguration build() {
            return new SystemTestInstanceConfiguration(this);
        }
    }
}
