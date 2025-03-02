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

import software.amazon.awscdk.NestedStack;
import software.constructs.Construct;

import sleeper.configuration.properties.instance.InstanceProperties;

public class IngestStatusStoreStack extends NestedStack {
    private final IngestStatusStoreResources resources;

    public IngestStatusStoreStack(
            Construct scope, String id, InstanceProperties instanceProperties, CoreStacks coreStacks) {
        super(scope, id);
        resources = IngestStatusStoreResources.from(this, instanceProperties, coreStacks);
    }

    public IngestStatusStoreResources getResources() {
        return resources;
    }
}
