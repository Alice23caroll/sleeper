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
package sleeper.core.statestore.transactionlog;

import sleeper.core.partition.PartitionsBuilder;
import sleeper.core.schema.Schema;
import sleeper.core.statestore.FileReferenceFactory;
import sleeper.core.statestore.StateStore;
import sleeper.core.statestore.StateStoreException;
import sleeper.core.util.ExponentialBackoffWithJitter;

import static sleeper.core.statestore.FileReferenceTestData.DEFAULT_UPDATE_TIME;
import static sleeper.core.table.TableStatusTestHelper.uniqueIdAndName;
import static sleeper.core.util.ExponentialBackoffWithJitterTestHelper.fixJitterSeed;
import static sleeper.core.util.ExponentialBackoffWithJitterTestHelper.noWaits;

public class InMemoryTransactionLogStateStoreTestBase {

    private PartitionsBuilder partitions;
    protected FileReferenceFactory factory;
    protected StateStore store;

    protected void initialiseWithSchema(Schema schema) throws Exception {
        createStore(new PartitionsBuilder(schema).singlePartition("root"));
        store.initialise();
    }

    protected void initialiseWithPartitions(PartitionsBuilder partitions) throws Exception {
        createStore(partitions);
        store.initialise(partitions.buildList());
    }

    private void createStore(PartitionsBuilder partitions) {
        this.partitions = partitions;
        factory = FileReferenceFactory.fromUpdatedAt(partitions.buildTree(), DEFAULT_UPDATE_TIME);
        store = TransactionLogStateStore.builder()
                .sleeperTable(uniqueIdAndName("test-table-id", "test-table"))
                .schema(partitions.getSchema())
                .filesLogStore(new InMemoryTransactionLogStore())
                .partitionsLogStore(new InMemoryTransactionLogStore())
                .retryBackoff(new ExponentialBackoffWithJitter(
                        TransactionLogStateStore.RETRY_WAIT_RANGE, fixJitterSeed(), noWaits()))
                .build();
        store.fixFileUpdateTime(DEFAULT_UPDATE_TIME);
    }

    protected void splitPartition(String parentId, String leftId, String rightId, long splitPoint) throws StateStoreException {
        partitions.splitToNewChildren(parentId, leftId, rightId, splitPoint)
                .applySplit(store, parentId);
        factory = FileReferenceFactory.fromUpdatedAt(partitions.buildTree(), DEFAULT_UPDATE_TIME);
    }
}
