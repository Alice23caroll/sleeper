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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.lang3.tuple.Pair;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.CfnOutputProps;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.NestedStack;
import software.amazon.awscdk.services.autoscaling.AutoScalingGroup;
import software.amazon.awscdk.services.autoscaling.BlockDevice;
import software.amazon.awscdk.services.autoscaling.BlockDeviceVolume;
import software.amazon.awscdk.services.autoscaling.CfnAutoScalingGroup;
import software.amazon.awscdk.services.autoscaling.EbsDeviceOptions;
import software.amazon.awscdk.services.autoscaling.EbsDeviceVolumeType;
import software.amazon.awscdk.services.cloudwatch.IMetric;
import software.amazon.awscdk.services.ec2.IVpc;
import software.amazon.awscdk.services.ec2.InstanceClass;
import software.amazon.awscdk.services.ec2.InstanceSize;
import software.amazon.awscdk.services.ec2.InstanceType;
import software.amazon.awscdk.services.ec2.UserData;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ec2.VpcLookupOptions;
import software.amazon.awscdk.services.ecr.IRepository;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecs.AddAutoScalingGroupCapacityOptions;
import software.amazon.awscdk.services.ecs.AmiHardwareType;
import software.amazon.awscdk.services.ecs.AsgCapacityProvider;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.ecs.ContainerDefinitionOptions;
import software.amazon.awscdk.services.ecs.ContainerImage;
import software.amazon.awscdk.services.ecs.CpuArchitecture;
import software.amazon.awscdk.services.ecs.Ec2TaskDefinition;
import software.amazon.awscdk.services.ecs.EcsOptimizedImage;
import software.amazon.awscdk.services.ecs.EcsOptimizedImageOptions;
import software.amazon.awscdk.services.ecs.FargateTaskDefinition;
import software.amazon.awscdk.services.ecs.ITaskDefinition;
import software.amazon.awscdk.services.ecs.MachineImageType;
import software.amazon.awscdk.services.ecs.NetworkMode;
import software.amazon.awscdk.services.ecs.OperatingSystemFamily;
import software.amazon.awscdk.services.ecs.RuntimePlatform;
import software.amazon.awscdk.services.events.Rule;
import software.amazon.awscdk.services.events.Schedule;
import software.amazon.awscdk.services.events.targets.LambdaFunction;
import software.amazon.awscdk.services.iam.IRole;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.lambda.IFunction;
import software.amazon.awscdk.services.lambda.Permission;
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
import sleeper.configuration.Requirements;
import sleeper.configuration.properties.SleeperScheduleRule;
import sleeper.configuration.properties.instance.InstanceProperties;
import sleeper.core.ContainerConstants;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import static sleeper.cdk.Utils.createAlarmForDlq;
import static sleeper.cdk.Utils.createLambdaLogGroup;
import static sleeper.cdk.Utils.shouldDeployPaused;
import static sleeper.configuration.properties.instance.CdkDefinedInstanceProperty.COMPACTION_AUTO_SCALING_GROUP;
import static sleeper.configuration.properties.instance.CdkDefinedInstanceProperty.COMPACTION_CLUSTER;
import static sleeper.configuration.properties.instance.CdkDefinedInstanceProperty.COMPACTION_JOB_CREATION_BATCH_DLQ_ARN;
import static sleeper.configuration.properties.instance.CdkDefinedInstanceProperty.COMPACTION_JOB_CREATION_BATCH_DLQ_URL;
import static sleeper.configuration.properties.instance.CdkDefinedInstanceProperty.COMPACTION_JOB_CREATION_BATCH_QUEUE_ARN;
import static sleeper.configuration.properties.instance.CdkDefinedInstanceProperty.COMPACTION_JOB_CREATION_BATCH_QUEUE_URL;
import static sleeper.configuration.properties.instance.CdkDefinedInstanceProperty.COMPACTION_JOB_CREATION_CLOUDWATCH_RULE;
import static sleeper.configuration.properties.instance.CdkDefinedInstanceProperty.COMPACTION_JOB_CREATION_TRIGGER_LAMBDA_FUNCTION;
import static sleeper.configuration.properties.instance.CdkDefinedInstanceProperty.COMPACTION_JOB_DLQ_ARN;
import static sleeper.configuration.properties.instance.CdkDefinedInstanceProperty.COMPACTION_JOB_DLQ_URL;
import static sleeper.configuration.properties.instance.CdkDefinedInstanceProperty.COMPACTION_JOB_QUEUE_ARN;
import static sleeper.configuration.properties.instance.CdkDefinedInstanceProperty.COMPACTION_JOB_QUEUE_URL;
import static sleeper.configuration.properties.instance.CdkDefinedInstanceProperty.COMPACTION_TASK_CREATION_CLOUDWATCH_RULE;
import static sleeper.configuration.properties.instance.CdkDefinedInstanceProperty.COMPACTION_TASK_CREATION_LAMBDA_FUNCTION;
import static sleeper.configuration.properties.instance.CdkDefinedInstanceProperty.COMPACTION_TASK_EC2_DEFINITION_FAMILY;
import static sleeper.configuration.properties.instance.CdkDefinedInstanceProperty.COMPACTION_TASK_FARGATE_DEFINITION_FAMILY;
import static sleeper.configuration.properties.instance.CdkDefinedInstanceProperty.VERSION;
import static sleeper.configuration.properties.instance.CommonProperty.ACCOUNT;
import static sleeper.configuration.properties.instance.CommonProperty.ID;
import static sleeper.configuration.properties.instance.CommonProperty.REGION;
import static sleeper.configuration.properties.instance.CommonProperty.TABLE_BATCHING_LAMBDAS_MEMORY_IN_MB;
import static sleeper.configuration.properties.instance.CommonProperty.TABLE_BATCHING_LAMBDAS_TIMEOUT_IN_SECONDS;
import static sleeper.configuration.properties.instance.CommonProperty.TASK_RUNNER_LAMBDA_MEMORY_IN_MB;
import static sleeper.configuration.properties.instance.CommonProperty.TASK_RUNNER_LAMBDA_TIMEOUT_IN_SECONDS;
import static sleeper.configuration.properties.instance.CommonProperty.VPC_ID;
import static sleeper.configuration.properties.instance.CompactionProperty.COMPACTION_EC2_POOL_DESIRED;
import static sleeper.configuration.properties.instance.CompactionProperty.COMPACTION_EC2_POOL_MAXIMUM;
import static sleeper.configuration.properties.instance.CompactionProperty.COMPACTION_EC2_POOL_MINIMUM;
import static sleeper.configuration.properties.instance.CompactionProperty.COMPACTION_EC2_ROOT_SIZE;
import static sleeper.configuration.properties.instance.CompactionProperty.COMPACTION_EC2_TYPE;
import static sleeper.configuration.properties.instance.CompactionProperty.COMPACTION_ECS_LAUNCHTYPE;
import static sleeper.configuration.properties.instance.CompactionProperty.COMPACTION_JOB_CREATION_LAMBDA_MEMORY_IN_MB;
import static sleeper.configuration.properties.instance.CompactionProperty.COMPACTION_JOB_CREATION_LAMBDA_PERIOD_IN_MINUTES;
import static sleeper.configuration.properties.instance.CompactionProperty.COMPACTION_JOB_CREATION_LAMBDA_TIMEOUT_IN_SECONDS;
import static sleeper.configuration.properties.instance.CompactionProperty.COMPACTION_QUEUE_VISIBILITY_TIMEOUT_IN_SECONDS;
import static sleeper.configuration.properties.instance.CompactionProperty.COMPACTION_TASK_CPU_ARCHITECTURE;
import static sleeper.configuration.properties.instance.CompactionProperty.COMPACTION_TASK_CREATION_PERIOD_IN_MINUTES;
import static sleeper.configuration.properties.instance.CompactionProperty.ECR_COMPACTION_REPO;
import static software.amazon.awscdk.services.lambda.Runtime.JAVA_11;

/**
 * Deploys the resources needed to perform compaction jobs. Specifically, there is:
 * <p>
 * - A lambda, that is periodically triggered by a CloudWatch rule, to query the state store for
 * information about active files with no job id, to create compaction job definitions as
 * appropriate and post them to a queue.
 * - An ECS {@link Cluster} and either a {@link FargateTaskDefinition} or a {@link Ec2TaskDefinition}
 * for tasks that will perform compaction jobs.
 * - A lambda, that is periodically triggered by a CloudWatch rule, to look at the
 * size of the queue and the number of running tasks and create more tasks if necessary.
 */
public class CompactionStack extends NestedStack {
    public static final String COMPACTION_STACK_QUEUE_URL = "CompactionStackQueueUrlKey";
    public static final String COMPACTION_STACK_DLQ_URL = "CompactionStackDLQUrlKey";
    public static final String COMPACTION_CLUSTER_NAME = "CompactionClusterName";

    private Queue compactionJobQ;
    private Queue compactionDLQ;
    private final InstanceProperties instanceProperties;
    private final CompactionStatusStoreResources statusStore;

    public CompactionStack(
            Construct scope,
            String id,
            InstanceProperties instanceProperties,
            BuiltJars jars,
            Topic topic,
            CoreStacks coreStacks,
            List<IMetric> errorMetrics) {
        super(scope, id);
        this.instanceProperties = instanceProperties;
        statusStore = CompactionStatusStoreResources.from(this, instanceProperties, coreStacks);
        // The compaction stack consists of the following components:
        // - An SQS queue for the compaction jobs.
        // - A lambda to periodically check for compaction jobs that should be created.
        //   This lambda is fired periodically by a CloudWatch rule. It queries the
        //   StateStore for information about the current partitions and files,
        //   identifies files that should be compacted, creates a job definition
        //   and sends it to an SQS queue.
        // - An ECS cluster, task definition, etc., for compaction jobs.
        // - A lambda that periodically checks the number of running compaction tasks
        //   and if there are not enough (i.e. there is a backlog on the queue
        //   then it creates more tasks).

        // Jars bucket
        IBucket jarsBucket = Bucket.fromBucketName(this, "JarsBucket", jars.bucketName());
        LambdaCode jobCreatorJar = jars.lambdaCode(BuiltJar.COMPACTION_JOB_CREATOR, jarsBucket);
        LambdaCode taskCreatorJar = jars.lambdaCode(BuiltJar.COMPACTION_TASK_CREATOR, jarsBucket);

        // SQS queue for the compaction jobs
        Queue compactionJobsQueue = sqsQueueForCompactionJobs(coreStacks, topic, errorMetrics);

        // Lambda to periodically check for compaction jobs that should be created
        lambdaToCreateCompactionJobsBatchedViaSQS(coreStacks, topic, errorMetrics, jarsBucket, jobCreatorJar, compactionJobsQueue);

        // ECS cluster for compaction tasks
        ecsClusterForCompactionTasks(coreStacks, jarsBucket, taskCreatorJar, compactionJobsQueue);

        // Lambda to create compaction tasks
        lambdaToCreateCompactionTasks(coreStacks, taskCreatorJar, compactionJobsQueue);

        Utils.addStackTagIfSet(this, instanceProperties);
    }

    private Queue sqsQueueForCompactionJobs(CoreStacks coreStacks, Topic topic, List<IMetric> errorMetrics) {
        // Create queue for compaction job definitions
        String dlQueueName = Utils.truncateTo64Characters(instanceProperties.get(ID) + "-CompactionJobDLQ");
        compactionDLQ = Queue.Builder
                .create(this, "CompactionJobDefinitionsDeadLetterQueue")
                .queueName(dlQueueName)
                .build();
        DeadLetterQueue compactionJobDefinitionsDeadLetterQueue = DeadLetterQueue.builder()
                .maxReceiveCount(3)
                .queue(compactionDLQ)
                .build();
        String queueName = Utils.truncateTo64Characters(instanceProperties.get(ID) + "-CompactionJobQ");
        compactionJobQ = Queue.Builder
                .create(this, "CompactionJobDefinitionsQueue")
                .queueName(queueName)
                .deadLetterQueue(compactionJobDefinitionsDeadLetterQueue)
                .visibilityTimeout(
                        Duration.seconds(instanceProperties.getInt(COMPACTION_QUEUE_VISIBILITY_TIMEOUT_IN_SECONDS)))
                .build();
        compactionJobQ.grantPurge(coreStacks.getPurgeQueuesPolicy());
        instanceProperties.set(COMPACTION_JOB_QUEUE_URL, compactionJobQ.getQueueUrl());
        instanceProperties.set(COMPACTION_JOB_QUEUE_ARN, compactionJobQ.getQueueArn());
        instanceProperties.set(COMPACTION_JOB_DLQ_URL,
                compactionJobDefinitionsDeadLetterQueue.getQueue().getQueueUrl());
        instanceProperties.set(COMPACTION_JOB_DLQ_ARN,
                compactionJobDefinitionsDeadLetterQueue.getQueue().getQueueArn());

        // Add alarm to send message to SNS if there are any messages on the dead letter queue
        createAlarmForDlq(this, "CompactionAlarm",
                "Alarms if there are any messages on the dead letter queue for the compactions queue",
                compactionDLQ, topic);
        errorMetrics.add(Utils.createErrorMetric("Compaction Errors", compactionDLQ, instanceProperties));

        CfnOutputProps compactionJobDefinitionsQueueProps = new CfnOutputProps.Builder()
                .value(compactionJobQ.getQueueUrl())
                .build();
        new CfnOutput(this, COMPACTION_STACK_QUEUE_URL, compactionJobDefinitionsQueueProps);
        CfnOutputProps compactionJobDefinitionsDLQueueProps = new CfnOutputProps.Builder()
                .value(compactionJobDefinitionsDeadLetterQueue.getQueue().getQueueUrl())
                .build();
        new CfnOutput(this, COMPACTION_STACK_DLQ_URL, compactionJobDefinitionsDLQueueProps);

        return compactionJobQ;
    }

    private void lambdaToCreateCompactionJobsBatchedViaSQS(
            CoreStacks coreStacks, Topic topic, List<IMetric> errorMetrics,
            IBucket jarsBucket, LambdaCode jobCreatorJar, Queue compactionJobsQueue) {

        // Function to create compaction jobs
        Map<String, String> environmentVariables = Utils.createDefaultEnvironment(instanceProperties);

        String triggerFunctionName = Utils.truncateTo64Characters(String.join("-", "sleeper",
                instanceProperties.get(ID).toLowerCase(Locale.ROOT), "compaction-job-creation-trigger"));
        String functionName = Utils.truncateTo64Characters(String.join("-", "sleeper",
                instanceProperties.get(ID).toLowerCase(Locale.ROOT), "compaction-job-creation-handler"));

        IFunction triggerFunction = jobCreatorJar.buildFunction(this, "CompactionJobsCreationTrigger", builder -> builder
                .functionName(triggerFunctionName)
                .description("Create batches of tables and send requests to create compaction jobs for those batches")
                .runtime(JAVA_11)
                .memorySize(instanceProperties.getInt(TABLE_BATCHING_LAMBDAS_MEMORY_IN_MB))
                .timeout(Duration.seconds(instanceProperties.getInt(TABLE_BATCHING_LAMBDAS_TIMEOUT_IN_SECONDS)))
                .handler("sleeper.compaction.job.creation.lambda.CreateCompactionJobsTriggerLambda::handleRequest")
                .environment(environmentVariables)
                .reservedConcurrentExecutions(1)
                .logGroup(createLambdaLogGroup(this, "CompactionJobsCreationTriggerLogGroup", triggerFunctionName, instanceProperties)));

        IFunction handlerFunction = jobCreatorJar.buildFunction(this, "CompactionJobsCreationHandler", builder -> builder
                .functionName(functionName)
                .description("Scan DynamoDB looking for files that need compacting and create appropriate job specs in DynamoDB")
                .runtime(JAVA_11)
                .memorySize(instanceProperties.getInt(COMPACTION_JOB_CREATION_LAMBDA_MEMORY_IN_MB))
                .timeout(Duration.seconds(instanceProperties.getInt(COMPACTION_JOB_CREATION_LAMBDA_TIMEOUT_IN_SECONDS)))
                .handler("sleeper.compaction.job.creation.lambda.CreateCompactionJobsLambda::handleRequest")
                .environment(environmentVariables)
                .logGroup(createLambdaLogGroup(this, "CompactionJobsCreationHandlerLogGroup", functionName, instanceProperties)));

        // Send messages from the trigger function to the handler function
        Queue jobCreationQueue = sqsQueueForCompactionJobCreation(topic, errorMetrics);
        handlerFunction.addEventSource(new SqsEventSource(jobCreationQueue,
                SqsEventSourceProps.builder().batchSize(1).build()));

        // Grant permissions
        // - Read through tables in trigger, send batches
        // - Read/write for creating compaction jobs, access to jars bucket for compaction strategies
        jobCreationQueue.grantSendMessages(triggerFunction);
        coreStacks.grantReadTablesStatus(triggerFunction);
        coreStacks.grantCreateCompactionJobs(handlerFunction);
        jarsBucket.grantRead(handlerFunction);
        statusStore.grantWriteJobEvent(handlerFunction);
        compactionJobsQueue.grantSendMessages(handlerFunction);
        coreStacks.grantInvokeScheduled(triggerFunction, jobCreationQueue);
        statusStore.grantWriteJobEvent(coreStacks.getInvokeCompactionPolicy());
        coreStacks.grantReadTablesStatus(coreStacks.getInvokeCompactionPolicy());
        coreStacks.grantCreateCompactionJobs(coreStacks.getInvokeCompactionPolicy());
        compactionJobsQueue.grantSendMessages(coreStacks.getInvokeCompactionPolicy());

        // Cloudwatch rule to trigger this lambda
        Rule rule = Rule.Builder
                .create(this, "CompactionJobCreationPeriodicTrigger")
                .ruleName(SleeperScheduleRule.COMPACTION_JOB_CREATION.buildRuleName(instanceProperties))
                .description("A rule to periodically trigger the compaction job creation lambda")
                .enabled(!shouldDeployPaused(this))
                .schedule(Schedule.rate(Duration.minutes(instanceProperties.getInt(COMPACTION_JOB_CREATION_LAMBDA_PERIOD_IN_MINUTES))))
                .targets(List.of(new LambdaFunction(triggerFunction)))
                .build();
        instanceProperties.set(COMPACTION_JOB_CREATION_TRIGGER_LAMBDA_FUNCTION, triggerFunction.getFunctionName());
        instanceProperties.set(COMPACTION_JOB_CREATION_CLOUDWATCH_RULE, rule.getRuleName());
    }

    private Queue sqsQueueForCompactionJobCreation(Topic topic, List<IMetric> errorMetrics) {
        // Create queue for compaction job creation invocation
        Queue deadLetterQueue = Queue.Builder
                .create(this, "CompactionJobCreationDLQ")
                .queueName(Utils.truncateTo64Characters(instanceProperties.get(ID) + "-CompactionJobCreationDLQ"))
                .build();
        Queue queue = Queue.Builder
                .create(this, "CompactionJobCreationQueue")
                .queueName(Utils.truncateTo64Characters(instanceProperties.get(ID) + "-CompactionJobCreationQ"))
                .deadLetterQueue(DeadLetterQueue.builder()
                        .maxReceiveCount(1)
                        .queue(deadLetterQueue)
                        .build())
                .visibilityTimeout(
                        Duration.seconds(instanceProperties.getInt(COMPACTION_JOB_CREATION_LAMBDA_TIMEOUT_IN_SECONDS)))
                .build();
        instanceProperties.set(COMPACTION_JOB_CREATION_BATCH_QUEUE_URL, queue.getQueueUrl());
        instanceProperties.set(COMPACTION_JOB_CREATION_BATCH_QUEUE_ARN, queue.getQueueArn());
        instanceProperties.set(COMPACTION_JOB_CREATION_BATCH_DLQ_URL, deadLetterQueue.getQueueUrl());
        instanceProperties.set(COMPACTION_JOB_CREATION_BATCH_DLQ_ARN, deadLetterQueue.getQueueArn());

        createAlarmForDlq(this, "CompactionJobCreationBatchAlarm",
                "Alarms if there are any messages on the dead letter queue for the compaction job creation batch queue",
                deadLetterQueue, topic);
        errorMetrics.add(Utils.createErrorMetric("Compaction Batching Errors", deadLetterQueue, instanceProperties));
        return queue;
    }

    private void ecsClusterForCompactionTasks(
            CoreStacks coreStacks, IBucket jarsBucket, LambdaCode taskCreatorJar, Queue compactionJobsQueue) {
        VpcLookupOptions vpcLookupOptions = VpcLookupOptions.builder()
                .vpcId(instanceProperties.get(VPC_ID))
                .build();
        IVpc vpc = Vpc.fromLookup(this, "VPC1", vpcLookupOptions);
        String clusterName = Utils.truncateTo64Characters(String.join("-", "sleeper",
                instanceProperties.get(ID).toLowerCase(Locale.ROOT), "compaction-cluster"));
        Cluster cluster = Cluster.Builder
                .create(this, "CompactionCluster")
                .clusterName(clusterName)
                .containerInsights(Boolean.TRUE)
                .vpc(vpc)
                .build();
        instanceProperties.set(COMPACTION_CLUSTER, cluster.getClusterName());

        IRepository repository = Repository.fromRepositoryName(this, "ECR1",
                instanceProperties.get(ECR_COMPACTION_REPO));
        ContainerImage containerImage = ContainerImage.fromEcrRepository(repository, instanceProperties.get(VERSION));

        Map<String, String> environmentVariables = Utils.createDefaultEnvironment(instanceProperties);
        environmentVariables.put(Utils.AWS_REGION, instanceProperties.get(REGION));

        Consumer<ITaskDefinition> grantPermissions = taskDef -> {
            coreStacks.grantRunCompactionJobs(taskDef.getTaskRole());
            jarsBucket.grantRead(taskDef.getTaskRole());
            statusStore.grantWriteJobEvent(taskDef.getTaskRole());
            statusStore.grantWriteTaskEvent(taskDef.getTaskRole());

            taskDef.getTaskRole().addToPrincipalPolicy(PolicyStatement.Builder
                    .create()
                    .resources(Collections.singletonList("*"))
                    .actions(List.of("ecs:DescribeContainerInstances"))
                    .build());

            compactionJobsQueue.grantConsumeMessages(taskDef.getTaskRole());
        };

        String launchType = instanceProperties.get(COMPACTION_ECS_LAUNCHTYPE);
        if (launchType.equalsIgnoreCase("FARGATE")) {
            FargateTaskDefinition fargateTaskDefinition = compactionFargateTaskDefinition();
            String fargateTaskDefinitionFamily = fargateTaskDefinition.getFamily();
            instanceProperties.set(COMPACTION_TASK_FARGATE_DEFINITION_FAMILY, fargateTaskDefinitionFamily);
            ContainerDefinitionOptions fargateContainerDefinitionOptions = createFargateContainerDefinition(containerImage,
                    environmentVariables, instanceProperties);
            fargateTaskDefinition.addContainer(ContainerConstants.COMPACTION_CONTAINER_NAME,
                    fargateContainerDefinitionOptions);
            grantPermissions.accept(fargateTaskDefinition);
        } else {
            Ec2TaskDefinition ec2TaskDefinition = compactionEC2TaskDefinition();
            String ec2TaskDefinitionFamily = ec2TaskDefinition.getFamily();
            instanceProperties.set(COMPACTION_TASK_EC2_DEFINITION_FAMILY, ec2TaskDefinitionFamily);
            ContainerDefinitionOptions ec2ContainerDefinitionOptions = createEC2ContainerDefinition(containerImage,
                    environmentVariables, instanceProperties);
            ec2TaskDefinition.addContainer(ContainerConstants.COMPACTION_CONTAINER_NAME, ec2ContainerDefinitionOptions);
            grantPermissions.accept(ec2TaskDefinition);
            addEC2CapacityProvider(cluster, vpc, coreStacks, taskCreatorJar);
        }

        CfnOutputProps compactionClusterProps = new CfnOutputProps.Builder()
                .value(cluster.getClusterName())
                .build();
        new CfnOutput(this, COMPACTION_CLUSTER_NAME, compactionClusterProps);
    }

    @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
    private void addEC2CapacityProvider(
            Cluster cluster, IVpc vpc, CoreStacks coreStacks, LambdaCode taskCreatorJar) {

        // Create some extra user data to enable ECS container metadata file
        UserData customUserData = UserData.forLinux();
        customUserData.addCommands("echo ECS_ENABLE_CONTAINER_METADATA=true >> /etc/ecs/ecs.config");

        AutoScalingGroup ec2scalingGroup = AutoScalingGroup.Builder.create(this, "CompactionScalingGroup").vpc(vpc)
                .allowAllOutbound(true)
                .associatePublicIpAddress(false)
                .requireImdsv2(true)
                .userData(customUserData)
                .blockDevices(List.of(BlockDevice.builder()
                        .deviceName("/dev/xvda") // root volume
                        .volume(BlockDeviceVolume.ebs(instanceProperties.getInt(COMPACTION_EC2_ROOT_SIZE),
                                EbsDeviceOptions.builder()
                                        .deleteOnTermination(true)
                                        .encrypted(true)
                                        .volumeType(EbsDeviceVolumeType.GP2)
                                        .build()))
                        .build()))
                .minCapacity(instanceProperties.getInt(COMPACTION_EC2_POOL_MINIMUM))
                .desiredCapacity(instanceProperties.getInt(COMPACTION_EC2_POOL_DESIRED))
                .maxCapacity(instanceProperties.getInt(COMPACTION_EC2_POOL_MAXIMUM)).requireImdsv2(true)
                .instanceType(lookupEC2InstanceType(instanceProperties.get(COMPACTION_EC2_TYPE)))
                .machineImage(EcsOptimizedImage.amazonLinux2(AmiHardwareType.STANDARD,
                        EcsOptimizedImageOptions.builder()
                                .cachedInContext(false)
                                .build()))
                .build();

        IFunction customTermination = lambdaForCustomTerminationPolicy(coreStacks, taskCreatorJar);
        // Set this by accessing underlying CloudFormation as CDK doesn't yet support custom
        // lambda termination policies: https://github.com/aws/aws-cdk/issues/19750
        ((CfnAutoScalingGroup) Objects.requireNonNull(ec2scalingGroup.getNode().getDefaultChild()))
                .setTerminationPolicies(List.of(customTermination.getFunctionArn()));

        customTermination.addPermission("AutoscalingCall", Permission.builder()
                .action("lambda:InvokeFunction")
                .principal(Role.fromRoleArn(this, "compaction_role_arn", "arn:aws:iam::" + instanceProperties.get(ACCOUNT)
                        + ":role/aws-service-role/autoscaling.amazonaws.com/AWSServiceRoleForAutoScaling"))
                .build());

        AsgCapacityProvider ec2Provider = AsgCapacityProvider.Builder
                .create(this, "CompactionCapacityProvider")
                .enableManagedScaling(false)
                .enableManagedTerminationProtection(false)
                .autoScalingGroup(ec2scalingGroup)
                .spotInstanceDraining(true)
                .canContainersAccessInstanceRole(false)
                .machineImageType(MachineImageType.AMAZON_LINUX_2)
                .build();

        cluster.addAsgCapacityProvider(ec2Provider,
                AddAutoScalingGroupCapacityOptions.builder()
                        .canContainersAccessInstanceRole(false)
                        .machineImageType(MachineImageType.AMAZON_LINUX_2)
                        .spotInstanceDraining(true)
                        .build());

        instanceProperties.set(COMPACTION_AUTO_SCALING_GROUP, ec2scalingGroup.getAutoScalingGroupName());
    }

    public static InstanceType lookupEC2InstanceType(String ec2InstanceType) {
        Objects.requireNonNull(ec2InstanceType, "instance type cannot be null");
        int pos = ec2InstanceType.indexOf('.');

        if (ec2InstanceType.trim().isEmpty() || pos < 0 || (pos + 1) >= ec2InstanceType.length()) {
            throw new IllegalArgumentException("instance type is empty or invalid");
        }

        String family = ec2InstanceType.substring(0, pos).toUpperCase(Locale.getDefault());
        String size = ec2InstanceType.substring(pos + 1).toUpperCase(Locale.getDefault());

        // Since Java identifiers can't start with a number, sizes like "2xlarge"
        // become "xlarge2" in the enum namespace.
        String normalisedSize = Utils.normaliseSize(size);

        // Now perform lookup of these against known types
        InstanceClass instanceClass = InstanceClass.valueOf(family);
        InstanceSize instanceSize = InstanceSize.valueOf(normalisedSize);

        return InstanceType.of(instanceClass, instanceSize);
    }

    private FargateTaskDefinition compactionFargateTaskDefinition() {
        String architecture = instanceProperties.get(COMPACTION_TASK_CPU_ARCHITECTURE).toUpperCase(Locale.ROOT);
        Pair<Integer, Integer> requirements = Requirements.getArchRequirements(architecture, instanceProperties);
        return FargateTaskDefinition.Builder
                .create(this, "CompactionFargateTaskDefinition")
                .family(instanceProperties.get(ID) + "CompactionFargateTaskFamily")
                .cpu(requirements.getLeft())
                .memoryLimitMiB(requirements.getRight())
                .runtimePlatform(RuntimePlatform.builder()
                        .cpuArchitecture(CpuArchitecture.of(architecture))
                        .operatingSystemFamily(OperatingSystemFamily.LINUX)
                        .build())
                .build();
    }

    private Ec2TaskDefinition compactionEC2TaskDefinition() {
        return Ec2TaskDefinition.Builder
                .create(this, "CompactionEC2TaskDefinition")
                .family(instanceProperties.get(ID) + "CompactionEC2TaskFamily")
                .networkMode(NetworkMode.BRIDGE)
                .build();
    }

    private ContainerDefinitionOptions createFargateContainerDefinition(
            ContainerImage image, Map<String, String> environment, InstanceProperties instanceProperties) {
        String architecture = instanceProperties.get(COMPACTION_TASK_CPU_ARCHITECTURE).toUpperCase(Locale.ROOT);
        Pair<Integer, Integer> requirements = Requirements.getArchRequirements(architecture, instanceProperties);
        return ContainerDefinitionOptions.builder()
                .image(image)
                .environment(environment)
                .cpu(requirements.getLeft())
                .memoryLimitMiB(requirements.getRight())
                .logging(Utils.createECSContainerLogDriver(this, instanceProperties, "FargateCompactionTasks"))
                .build();
    }

    private ContainerDefinitionOptions createEC2ContainerDefinition(
            ContainerImage image, Map<String, String> environment, InstanceProperties instanceProperties) {
        String architecture = instanceProperties.get(COMPACTION_TASK_CPU_ARCHITECTURE).toUpperCase(Locale.ROOT);
        Pair<Integer, Integer> requirements = Requirements.getArchRequirements(architecture, instanceProperties);
        return ContainerDefinitionOptions.builder()
                .image(image)
                .environment(environment)
                .cpu(requirements.getLeft())
                // bit hacky: Reduce memory requirement for EC2 to prevent
                // container allocation failing when we need almost entire resources
                // of machine
                .memoryLimitMiB((int) (requirements.getRight() * 0.95))
                .logging(Utils.createECSContainerLogDriver(this, instanceProperties, "EC2CompactionTasks"))
                .build();
    }

    @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
    private IFunction lambdaForCustomTerminationPolicy(CoreStacks coreStacks, LambdaCode taskCreatorJar) {

        // Run tasks function
        Map<String, String> environmentVariables = Utils.createDefaultEnvironment(instanceProperties);

        String functionName = Utils.truncateTo64Characters(String.join("-", "sleeper",
                instanceProperties.get(ID).toLowerCase(Locale.ROOT), "compaction-custom-termination"));

        IFunction handler = taskCreatorJar.buildFunction(this, "CompactionTerminator", builder -> builder
                .functionName(functionName)
                .description("Custom termination policy for ECS auto scaling group. Only terminate empty instances.")
                .environment(environmentVariables)
                .handler("sleeper.compaction.task.creation.SafeTerminationLambda::handleRequest")
                .logGroup(createLambdaLogGroup(this, "CompactionTerminatorLogGroup", functionName, instanceProperties))
                .memorySize(512)
                .runtime(software.amazon.awscdk.services.lambda.Runtime.JAVA_11)
                .timeout(Duration.seconds(10)));

        coreStacks.grantReadInstanceConfig(handler);
        // Grant this function permission to query ECS for the number of tasks.
        PolicyStatement policyStatement = PolicyStatement.Builder
                .create()
                .resources(Collections.singletonList("*"))
                .actions(Arrays.asList("ecs:DescribeContainerInstances", "ecs:ListContainerInstances"))
                .build();
        IRole role = Objects.requireNonNull(handler.getRole());
        role.addToPrincipalPolicy(policyStatement);

        return handler;
    }

    @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
    private void lambdaToCreateCompactionTasks(
            CoreStacks coreStacks, LambdaCode taskCreatorJar, Queue compactionJobsQueue) {
        String functionName = Utils.truncateTo64Characters(String.join("-", "sleeper",
                instanceProperties.get(ID).toLowerCase(Locale.ROOT), "compaction-tasks-creator"));

        IFunction handler = taskCreatorJar.buildFunction(this, "CompactionTasksCreator", builder -> builder
                .functionName(functionName)
                .description("If there are compaction jobs on queue create tasks to run them")
                .runtime(software.amazon.awscdk.services.lambda.Runtime.JAVA_11)
                .memorySize(instanceProperties.getInt(TASK_RUNNER_LAMBDA_MEMORY_IN_MB))
                .timeout(Duration.seconds(instanceProperties.getInt(TASK_RUNNER_LAMBDA_TIMEOUT_IN_SECONDS)))
                .handler("sleeper.compaction.task.creation.RunCompactionTasksLambda::eventHandler")
                .environment(Utils.createDefaultEnvironment(instanceProperties))
                .reservedConcurrentExecutions(1)
                .logGroup(createLambdaLogGroup(this, "CompactionTasksCreatorLogGroup", functionName, instanceProperties)));

        // Grant this function permission to read from the S3 bucket
        coreStacks.grantReadInstanceConfig(handler);

        // Grant this function permission to query the queue for number of messages
        compactionJobsQueue.grant(handler, "sqs:GetQueueAttributes");
        compactionJobsQueue.grantSendMessages(handler);
        compactionJobsQueue.grantSendMessages(coreStacks.getInvokeCompactionPolicy());
        Utils.grantInvokeOnPolicy(handler, coreStacks.getInvokeCompactionPolicy());
        coreStacks.grantInvokeScheduled(handler);

        // Grant this function permission to query ECS for the number of tasks, etc
        PolicyStatement policyStatement = PolicyStatement.Builder
                .create()
                .resources(Collections.singletonList("*"))
                .actions(Arrays.asList("ecs:DescribeClusters", "ecs:RunTask", "iam:PassRole",
                        "ecs:DescribeContainerInstances", "ecs:DescribeTasks", "ecs:ListContainerInstances",
                        "autoscaling:SetDesiredCapacity", "autoscaling:DescribeAutoScalingGroups"))
                .build();
        IRole role = Objects.requireNonNull(handler.getRole());
        role.addToPrincipalPolicy(policyStatement);
        role.addManagedPolicy(ManagedPolicy.fromAwsManagedPolicyName("service-role/AmazonECSTaskExecutionRolePolicy"));

        // Cloudwatch rule to trigger this lambda
        Rule rule = Rule.Builder
                .create(this, "CompactionTasksCreationPeriodicTrigger")
                .ruleName(SleeperScheduleRule.COMPACTION_TASK_CREATION.buildRuleName(instanceProperties))
                .description("A rule to periodically trigger the compaction task creation lambda")
                .enabled(!shouldDeployPaused(this))
                .schedule(Schedule.rate(Duration.minutes(instanceProperties.getInt(COMPACTION_TASK_CREATION_PERIOD_IN_MINUTES))))
                .targets(Collections.singletonList(new LambdaFunction(handler)))
                .build();
        instanceProperties.set(COMPACTION_TASK_CREATION_LAMBDA_FUNCTION, handler.getFunctionName());
        instanceProperties.set(COMPACTION_TASK_CREATION_CLOUDWATCH_RULE, rule.getRuleName());
    }

    public Queue getCompactionJobsQueue() {
        return compactionJobQ;
    }

    public Queue getCompactionDeadLetterQueue() {
        return compactionDLQ;
    }

}
