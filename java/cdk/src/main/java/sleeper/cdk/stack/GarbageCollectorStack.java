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

import software.amazon.awscdk.Duration;
import software.amazon.awscdk.NestedStack;
import software.amazon.awscdk.services.cloudwatch.IMetric;
import software.amazon.awscdk.services.events.Rule;
import software.amazon.awscdk.services.events.Schedule;
import software.amazon.awscdk.services.events.targets.LambdaFunction;
import software.amazon.awscdk.services.lambda.IFunction;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.lambda.eventsources.SqsEventSource;
import software.amazon.awscdk.services.lambda.eventsources.SqsEventSourceProps;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.IBucket;
import software.amazon.awscdk.services.sns.Topic;
import software.amazon.awscdk.services.sqs.DeadLetterQueue;
import software.amazon.awscdk.services.sqs.Queue;
import software.constructs.Construct;

import sleeper.cdk.Utils;
import sleeper.cdk.jars.BuiltJar;
import sleeper.cdk.jars.BuiltJars;
import sleeper.cdk.jars.LambdaCode;
import sleeper.configuration.properties.SleeperScheduleRule;
import sleeper.configuration.properties.instance.InstanceProperties;

import java.util.List;
import java.util.Locale;

import static sleeper.cdk.Utils.createAlarmForDlq;
import static sleeper.cdk.Utils.createLambdaLogGroup;
import static sleeper.cdk.Utils.shouldDeployPaused;
import static sleeper.configuration.properties.instance.CdkDefinedInstanceProperty.GARBAGE_COLLECTOR_CLOUDWATCH_RULE;
import static sleeper.configuration.properties.instance.CdkDefinedInstanceProperty.GARBAGE_COLLECTOR_DLQ_ARN;
import static sleeper.configuration.properties.instance.CdkDefinedInstanceProperty.GARBAGE_COLLECTOR_DLQ_URL;
import static sleeper.configuration.properties.instance.CdkDefinedInstanceProperty.GARBAGE_COLLECTOR_LAMBDA_FUNCTION;
import static sleeper.configuration.properties.instance.CdkDefinedInstanceProperty.GARBAGE_COLLECTOR_QUEUE_ARN;
import static sleeper.configuration.properties.instance.CdkDefinedInstanceProperty.GARBAGE_COLLECTOR_QUEUE_URL;
import static sleeper.configuration.properties.instance.CommonProperty.ID;
import static sleeper.configuration.properties.instance.CommonProperty.JARS_BUCKET;
import static sleeper.configuration.properties.instance.CommonProperty.TABLE_BATCHING_LAMBDAS_MEMORY_IN_MB;
import static sleeper.configuration.properties.instance.CommonProperty.TABLE_BATCHING_LAMBDAS_TIMEOUT_IN_SECONDS;
import static sleeper.configuration.properties.instance.GarbageCollectionProperty.GARBAGE_COLLECTOR_LAMBDA_MEMORY_IN_MB;
import static sleeper.configuration.properties.instance.GarbageCollectionProperty.GARBAGE_COLLECTOR_PERIOD_IN_MINUTES;

/**
 * Deploys resources to perform garbage collection. This will find and delete files which have been marked as being
 * ready for garbage collection after a compaction job.
 */
public class GarbageCollectorStack extends NestedStack {

    public GarbageCollectorStack(
            Construct scope, String id, InstanceProperties instanceProperties,
            BuiltJars jars, Topic topic, CoreStacks coreStacks, List<IMetric> errorMetrics) {
        super(scope, id);

        // Jars bucket
        IBucket jarsBucket = Bucket.fromBucketName(this, "JarsBucket", instanceProperties.get(JARS_BUCKET));

        // Garbage collector code
        LambdaCode gcJar = jars.lambdaCode(BuiltJar.GARBAGE_COLLECTOR, jarsBucket);

        String triggerFunctionName = Utils.truncateTo64Characters(String.join("-", "sleeper",
                instanceProperties.get(ID).toLowerCase(Locale.ROOT), "garbage-collector-trigger"));
        String functionName = Utils.truncateTo64Characters(String.join("-", "sleeper",
                instanceProperties.get(ID).toLowerCase(Locale.ROOT), "garbage-collector"));

        // Timeout is set to 90% of the period with which this runs to avoid 2 running simultaneously,
        // with a maximum of 900 seconds (15 minutes) which is the maximum execution time
        // of a lambda.
        int timeoutSecsFromGcPeriod = (int) (0.9 * 60 * instanceProperties.getInt(GARBAGE_COLLECTOR_PERIOD_IN_MINUTES));
        Duration handlerTimeout = Duration.seconds(Math.max(1, Math.min(timeoutSecsFromGcPeriod, 900)));
        Duration queueVisibilityTimeout = handlerTimeout.plus(Duration.seconds(10));

        // Garbage collector function
        IFunction triggerFunction = gcJar.buildFunction(this, "GarbageCollectorTrigger", builder -> builder
                .functionName(triggerFunctionName)
                .description("Creates batches of Sleeper tables to perform garbage collection for and puts them on a queue to be processed")
                .runtime(Runtime.JAVA_11)
                .handler("sleeper.garbagecollector.GarbageCollectorTriggerLambda::handleRequest")
                .environment(Utils.createDefaultEnvironment(instanceProperties))
                .memorySize(instanceProperties.getInt(TABLE_BATCHING_LAMBDAS_MEMORY_IN_MB))
                .timeout(Duration.seconds(instanceProperties.getInt(TABLE_BATCHING_LAMBDAS_TIMEOUT_IN_SECONDS)))
                .logGroup(createLambdaLogGroup(this, "GarbageCollectorTriggerLogGroup", triggerFunctionName, instanceProperties)));
        IFunction handlerFunction = gcJar.buildFunction(this, "GarbageCollectorLambda", builder -> builder
                .functionName(functionName)
                .description("Scan the state store looking for files that need deleting and delete them")
                .runtime(Runtime.JAVA_11)
                .memorySize(instanceProperties.getInt(GARBAGE_COLLECTOR_LAMBDA_MEMORY_IN_MB))
                .timeout(handlerTimeout)
                .handler("sleeper.garbagecollector.GarbageCollectorLambda::handleRequest")
                .environment(Utils.createDefaultEnvironment(instanceProperties))
                .logGroup(createLambdaLogGroup(this, "GarbageCollectorLambdaLogGroup", functionName, instanceProperties)));
        instanceProperties.set(GARBAGE_COLLECTOR_LAMBDA_FUNCTION, triggerFunction.getFunctionName());

        // Grant this function permission delete files from the data bucket and
        // to read from / write to the DynamoDB table
        coreStacks.grantGarbageCollection(handlerFunction);
        coreStacks.grantReadTablesStatus(triggerFunction);

        // Cloudwatch rule to trigger this lambda
        Rule rule = Rule.Builder
                .create(this, "GarbageCollectorPeriodicTrigger")
                .ruleName(SleeperScheduleRule.GARBAGE_COLLECTOR.buildRuleName(instanceProperties))
                .description("A rule to periodically trigger the garbage collector")
                .enabled(!shouldDeployPaused(this))
                .schedule(Schedule.rate(Duration.minutes(instanceProperties.getInt(GARBAGE_COLLECTOR_PERIOD_IN_MINUTES))))
                .targets(List.of(new LambdaFunction(triggerFunction)))
                .build();
        instanceProperties.set(GARBAGE_COLLECTOR_CLOUDWATCH_RULE, rule.getRuleName());

        String deadLetterQueueName = Utils.truncateTo64Characters(instanceProperties.get(ID) + "-GCJobDLQ");
        Queue deadLetterQueue = Queue.Builder
                .create(this, "GCJobDeadLetterQueue")
                .queueName(deadLetterQueueName)
                .build();
        String queueName = Utils.truncateTo64Characters(instanceProperties.get(ID) + "-GCJobQ");
        Queue queue = Queue.Builder
                .create(this, "GCJobQueue")
                .queueName(queueName)
                .deadLetterQueue(DeadLetterQueue.builder()
                        .maxReceiveCount(1)
                        .queue(deadLetterQueue)
                        .build())
                .visibilityTimeout(queueVisibilityTimeout)
                .build();
        instanceProperties.set(GARBAGE_COLLECTOR_QUEUE_URL, queue.getQueueUrl());
        instanceProperties.set(GARBAGE_COLLECTOR_QUEUE_ARN, queue.getQueueArn());
        instanceProperties.set(GARBAGE_COLLECTOR_DLQ_URL, deadLetterQueue.getQueueUrl());
        instanceProperties.set(GARBAGE_COLLECTOR_DLQ_ARN, deadLetterQueue.getQueueArn());
        createAlarmForDlq(this, "GarbageCollectorAlarm",
                "Alarms if there are any messages on the dead letter queue for the garbage collector queue",
                deadLetterQueue, topic);
        errorMetrics.add(Utils.createErrorMetric("Garbage Collection Errors", deadLetterQueue, instanceProperties));
        queue.grantSendMessages(triggerFunction);
        handlerFunction.addEventSource(new SqsEventSource(queue,
                SqsEventSourceProps.builder().batchSize(1).build()));
        coreStacks.grantInvokeScheduled(triggerFunction, queue);

        Utils.addStackTagIfSet(this, instanceProperties);
    }
}
