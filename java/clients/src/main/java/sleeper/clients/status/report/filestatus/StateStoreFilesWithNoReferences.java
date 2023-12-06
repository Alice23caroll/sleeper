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
package sleeper.clients.status.report.filestatus;

import sleeper.core.statestore.FileReferences;
import sleeper.core.statestore.StateStore;
import sleeper.core.statestore.StateStoreException;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class StateStoreFilesWithNoReferences {

    private final List<String> files;
    private final boolean moreThanMax;

    private StateStoreFilesWithNoReferences(List<String> files, boolean moreThanMax) {
        this.files = files;
        this.moreThanMax = moreThanMax;
    }

    public List<String> getFiles() {
        return files;
    }

    public Stream<String> stream() {
        return files.stream();
    }

    public boolean isMoreThanMax() {
        return moreThanMax;
    }

    public static StateStoreFilesWithNoReferences from(StateStore stateStore, int maxNumberOfFilesToCount) throws StateStoreException {
        List<String> filenamesWithNoReferences = stateStore.getAllFileReferences().getFiles().stream()
                .filter(fileReferences -> fileReferences.getReferences().isEmpty())
                .map(FileReferences::getFilename)
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.toList());
        boolean moreThanMax = filenamesWithNoReferences.size() > maxNumberOfFilesToCount;
        List<String> truncatedFilenames = filenamesWithNoReferences;
        if (moreThanMax) {
            truncatedFilenames = filenamesWithNoReferences.subList(0, maxNumberOfFilesToCount);
        }
        return new StateStoreFilesWithNoReferences(truncatedFilenames, moreThanMax);
    }

    public static StateStoreFilesWithNoReferences none() {
        return new StateStoreFilesWithNoReferences(List.of(), false);
    }

}
