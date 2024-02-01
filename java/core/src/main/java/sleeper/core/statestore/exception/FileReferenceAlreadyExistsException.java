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

package sleeper.core.statestore.exception;

import sleeper.core.statestore.FileReference;
import sleeper.core.statestore.StateStoreException;

public class FileReferenceAlreadyExistsException extends StateStoreException {
    public FileReferenceAlreadyExistsException(FileReference fileReference) {
        this(fileReference, null);
    }

    public FileReferenceAlreadyExistsException(FileReference fileReference, Exception cause) {
        super("Reference to file already exists in partition " + fileReference.getPartitionId()
                + ", filename " + fileReference.getFilename(), cause);
    }
}
