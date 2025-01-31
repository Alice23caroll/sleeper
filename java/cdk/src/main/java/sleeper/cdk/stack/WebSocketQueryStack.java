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
package sleeper.cdk.stack;

import software.amazon.awscdk.ArnComponents;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.CfnOutputProps;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.NestedStack;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.services.apigateway.IntegrationType;
import software.amazon.awscdk.services.apigatewayv2.CfnApi;
import software.amazon.awscdk.services.apigatewayv2.CfnIntegration;
import software.amazon.awscdk.services.apigatewayv2.CfnRoute;
import software.amazon.awscdk.services.apigatewayv2.WebSocketApi;
import software.amazon.awscdk.services.apigatewayv2.WebSocketApiAttributes;
import software.amazon.awscdk.services.apigatewayv2.WebSocketStage;
import software.amazon.awscdk.services.iam.Grant;
import software.amazon.awscdk.services.iam.GrantOnPrincipalOptions;
import software.amazon.awscdk.services.iam.IGrantable;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.lambda.IFunction;
import software.amazon.awscdk.services.lambda.Permission;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.IBucket;
import software.constructs.Construct;

import sleeper.cdk.Utils;
import sleeper.cdk.jars.BuiltJar;
import sleeper.cdk.jars.BuiltJars;
import sleeper.cdk.jars.LambdaCode;
import sleeper.configuration.properties.instance.CdkDefinedInstanceProperty;
import sleeper.configuration.properties.instance.InstanceProperties;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;

import static sleeper.cdk.Utils.createLambdaLogGroup;
import static sleeper.configuration.properties.instance.CommonProperty.ID;

public final class WebSocketQueryStack extends NestedStack {

    private CfnApi webSocketApi;

    public WebSocketQueryStack(Construct scope,
            String id,
            InstanceProperties instanceProperties,
            BuiltJars jars, CoreStacks coreStacks, QueryQueueStack queryQueueStack, QueryStack queryStack) {
        super(scope, id);

        IBucket jarsBucket = Bucket.fromBucketName(this, "JarsBucket", jars.bucketName());
        LambdaCode queryJar = jars.lambdaCode(BuiltJar.QUERY, jarsBucket);
        setupWebSocketApi(instanceProperties, queryJar, coreStacks, queryQueueStack, queryStack);
        Utils.addStackTagIfSet(this, instanceProperties);
    }

    /***
     * Creates the web socket API.
     *
     * @param instanceProperties containing configuration details
     * @param queryJar           the query jar lambda code
     * @param coreStacks         the core stacks this belongs to
     * @param queryQueueStack    the stack responsible for the query queue
     * @param queryStack         the stack responsible for the query lambdas
     */
    protected void setupWebSocketApi(InstanceProperties instanceProperties, LambdaCode queryJar,
            CoreStacks coreStacks, QueryQueueStack queryQueueStack, QueryStack queryStack) {
        Map<String, String> env = Utils.createDefaultEnvironment(instanceProperties);
        String functionName = Utils.truncateTo64Characters(String.join("-", "sleeper",
                instanceProperties.get(ID).toLowerCase(Locale.ROOT), "websocket-api-handler"));
        IFunction webSocketApiHandler = queryJar.buildFunction(this, "WebSocketApiHandler", builder -> builder
                .functionName(functionName)
                .description("Prepares queries received via the WebSocket API and queues them for processing")
                .handler("sleeper.query.lambda.WebSocketQueryProcessorLambda::handleRequest")
                .environment(env)
                .memorySize(256)
                .logGroup(createLambdaLogGroup(this, "WebSocketApiHandlerLogGroup", functionName, instanceProperties))
                .timeout(Duration.seconds(29))
                .runtime(software.amazon.awscdk.services.lambda.Runtime.JAVA_11));

        queryQueueStack.grantSendMessages(webSocketApiHandler);
        coreStacks.grantReadTablesConfig(webSocketApiHandler);

        CfnApi api = CfnApi.Builder.create(this, "api")
                .name("sleeper-" + instanceProperties.get(ID) + "-query-api")
                .description("Sleeper Query API")
                .protocolType("WEBSOCKET")
                .routeSelectionExpression("$request.body.action")
                .build();
        this.webSocketApi = api;

        String integrationUri = Stack.of(this).formatArn(ArnComponents.builder()
                .service("apigateway")
                .account("lambda")
                .resource("path/2015-03-31/functions")
                .resourceName(webSocketApiHandler.getFunctionArn() + "/invocations")
                .build());

        CfnIntegration integration = CfnIntegration.Builder.create(this, "integration")
                .apiId(api.getRef())
                .integrationType(IntegrationType.AWS_PROXY.name())
                .integrationUri(integrationUri)
                .build();

        // Note that we are deliberately using CFN L1 constructs to deploy the connect
        // route so that we are able to switch on AWS_IAM authentication. This is
        // currently not possible using the API Gateway L2 constructs
        CfnRoute.Builder.create(this, "connect-route")
                .apiId(api.getRef())
                .apiKeyRequired(false)
                .authorizationType("AWS_IAM")
                .routeKey("$connect")
                .target("integrations/" + integration.getRef())
                .build();

        CfnRoute.Builder.create(this, "default-route")
                .apiId(api.getRef())
                .apiKeyRequired(false)
                .routeKey("$default")
                .target("integrations/" + integration.getRef())
                .build();

        webSocketApiHandler.addPermission("apigateway-access-to-lambda", Permission.builder()
                .principal(new ServicePrincipal("apigateway.amazonaws.com"))
                .sourceArn(Stack.of(this).formatArn(ArnComponents.builder()
                        .service("execute-api")
                        .resource(api.getRef())
                        .resourceName("*/*")
                        .build()))
                .build());

        WebSocketStage stage = WebSocketStage.Builder.create(this, "stage")
                .webSocketApi(WebSocketApi.fromWebSocketApiAttributes(this, "imported-api", WebSocketApiAttributes.builder()
                        .webSocketId(api.getRef())
                        .build()))
                .stageName("live")
                .autoDeploy(true)
                .build();
        stage.grantManagementApiAccess(webSocketApiHandler);
        stage.grantManagementApiAccess(queryStack.getQueryExecutorLambda());
        stage.grantManagementApiAccess(queryStack.getLeafPartitionQueryLambda());
        grantAccessToWebSocketQueryApi(coreStacks.getQueryPolicy());

        new CfnOutput(this, "WebSocketApiUrl", CfnOutputProps.builder()
                .value(stage.getUrl())
                .build());
        instanceProperties.set(CdkDefinedInstanceProperty.QUERY_WEBSOCKET_API_URL, stage.getUrl());
    }

    /***
     * Grant access to the web socket query api.
     *
     * @param  identity item to grant access to
     * @return          a grant principal
     */
    public Grant grantAccessToWebSocketQueryApi(IGrantable identity) {
        return Grant.addToPrincipal(GrantOnPrincipalOptions.builder()
                .grantee(identity)
                .actions(Collections.singletonList("execute-api:Invoke"))
                .resourceArns(Collections.singletonList(Stack.of(this).formatArn(ArnComponents.builder()
                        .service("execute-api")
                        .resource(this.webSocketApi.getRef())
                        .build())
                        + "/live/*"))
                .build());
    }
}
