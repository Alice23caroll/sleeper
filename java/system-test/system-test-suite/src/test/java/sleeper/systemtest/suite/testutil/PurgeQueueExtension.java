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

package sleeper.systemtest.suite.testutil;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sleeper.configuration.properties.instance.InstanceProperty;
import sleeper.systemtest.suite.dsl.SleeperSystemTest;

import java.util.List;

public class PurgeQueueExtension implements AfterEachCallback {
    private static final Logger LOGGER = LoggerFactory.getLogger(PurgeQueueExtension.class);
    private final List<InstanceProperty> queueProperties;
    private final PurgeQueueRunner purgeQueueRunner;

    PurgeQueueExtension(List<InstanceProperty> queueProperties, PurgeQueueRunner purgeQueueRunner) {
        this.queueProperties = queueProperties;
        this.purgeQueueRunner = purgeQueueRunner;
    }

    public static PurgeQueueExtension purgeIfTestFailed(SleeperSystemTest sleeper, InstanceProperty... queueProperties) {
        return new PurgeQueueExtension(List.of(queueProperties), (queue) -> sleeper.ingest().purgeQueue(queue));
    }

    @Override
    public void afterEach(ExtensionContext testContext) throws InterruptedException {
        if (testContext.getExecutionException().isPresent()) {
            afterTestFailed();
        } else {
            afterTestPassed();
        }
    }

    public void afterTestFailed() throws InterruptedException {
        LOGGER.info("Test failed, purging queues: {}", queueProperties);
        for (InstanceProperty queueProperty : queueProperties) {
            purgeQueueRunner.purge(queueProperty);
        }
    }

    public void afterTestPassed() {
        LOGGER.info("Test passed, not purging queue");
    }

    public interface PurgeQueueRunner {
        void purge(InstanceProperty queueProperty) throws InterruptedException;
    }
}
