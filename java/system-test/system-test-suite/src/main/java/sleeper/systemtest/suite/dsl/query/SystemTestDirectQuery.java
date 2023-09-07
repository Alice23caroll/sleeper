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

package sleeper.systemtest.suite.dsl.query;

import sleeper.core.record.Record;
import sleeper.systemtest.drivers.query.DirectQueryDriver;
import sleeper.systemtest.drivers.query.QueryRange;

import java.util.List;

public class SystemTestDirectQuery {

    private final DirectQueryDriver directQueryDriver;

    public SystemTestDirectQuery(DirectQueryDriver directQueryDriver) {
        this.directQueryDriver = directQueryDriver;
    }

    public List<Record> allRecordsInTable() {
        return directQueryDriver.getAllRecordsInTable();
    }

    public List<Record> byRowKey(String key, QueryRange... ranges) {
        return directQueryDriver.run(key, List.of(ranges));
    }
}
