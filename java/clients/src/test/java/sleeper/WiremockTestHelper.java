/*
 * Copyright 2022-2023 Crown Copyright
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
package sleeper;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.ecr.AmazonECR;
import com.amazonaws.services.ecr.AmazonECRClient;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;

import static com.amazonaws.regions.Regions.DEFAULT_REGION;

public class WiremockTestHelper {

    public static final String WIREMOCK_ACCESS_KEY = "wiremock-access-key";
    public static final String WIREMOCK_SECRET_KEY = "wiremock-secret-key";

    public static AmazonECR wiremockEcrClient(WireMockRuntimeInfo runtimeInfo) {
        return AmazonECRClient.builder()
                .withEndpointConfiguration(endpointConfiguration(runtimeInfo))
                .withCredentials(credentialsProvider())
                .build();
    }

    private static AwsClientBuilder.EndpointConfiguration endpointConfiguration(WireMockRuntimeInfo runtimeInfo) {
        return new AwsClientBuilder.EndpointConfiguration(runtimeInfo.getHttpBaseUrl(), DEFAULT_REGION.getName());
    }

    private static AWSCredentialsProvider credentialsProvider() {
        return new AWSStaticCredentialsProvider(new BasicAWSCredentials(WIREMOCK_ACCESS_KEY, WIREMOCK_SECRET_KEY));
    }
}
