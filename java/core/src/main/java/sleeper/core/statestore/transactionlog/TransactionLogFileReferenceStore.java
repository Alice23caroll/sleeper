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

import sleeper.core.statestore.AllReferencesToAFile;
import sleeper.core.statestore.AllReferencesToAllFiles;
import sleeper.core.statestore.AssignJobIdRequest;
import sleeper.core.statestore.FileReference;
import sleeper.core.statestore.FileReferenceStore;
import sleeper.core.statestore.SplitFileReferenceRequest;
import sleeper.core.statestore.SplitRequestsFailedException;
import sleeper.core.statestore.StateStoreException;
import sleeper.core.statestore.transactionlog.transactions.AddFilesTransaction;
import sleeper.core.statestore.transactionlog.transactions.FileTransaction;
import sleeper.core.statestore.transactionlog.transactions.StateStoreFiles;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toUnmodifiableList;

class TransactionLogFileReferenceStore implements FileReferenceStore {

    private final TransactionLogStore logStore;
    private Clock clock = Clock.systemUTC();

    TransactionLogFileReferenceStore(TransactionLogStore logStore) {
        this.logStore = logStore;
    }

    @Override
    public void addFilesWithReferences(List<AllReferencesToAFile> files) throws StateStoreException {
        Instant updateTime = clock.instant();
        logStore.addTransaction(new AddFilesTransaction(files.stream()
                .map(file -> file.withCreatedUpdateTime(updateTime))
                .collect(toUnmodifiableList())));
    }

    @Override
    public void assignJobIds(List<AssignJobIdRequest> requests) throws StateStoreException {
    }

    @Override
    public void atomicallyReplaceFileReferencesWithNewOne(String jobId, String partitionId, List<String> inputFiles, FileReference newReference) throws StateStoreException {
    }

    @Override
    public void clearFileData() {
    }

    @Override
    public void deleteGarbageCollectedFileReferenceCounts(List<String> filenames) throws StateStoreException {
    }

    @Override
    public void fixTime(Instant time) {
        clock = Clock.fixed(time, ZoneId.of("UTC"));
    }

    @Override
    public AllReferencesToAllFiles getAllFilesWithMaxUnreferenced(int maxUnreferencedFiles) throws StateStoreException {
        List<AllReferencesToAFile> files = new ArrayList<>();
        int foundUnreferenced = 0;
        boolean moreThanMax = false;
        for (AllReferencesToAFile file : (Iterable<AllReferencesToAFile>) () -> files().referencedAndUnreferenced().iterator()) {
            if (file.getTotalReferenceCount() < 1) {
                if (foundUnreferenced >= maxUnreferencedFiles) {
                    moreThanMax = true;
                    continue;
                } else {
                    foundUnreferenced++;
                }
            }
            files.add(file);
        }
        return new AllReferencesToAllFiles(files, moreThanMax);
    }

    @Override
    public List<FileReference> getFileReferences() throws StateStoreException {
        return files().references().collect(toUnmodifiableList());
    }

    @Override
    public List<FileReference> getFileReferencesWithNoJobId() throws StateStoreException {
        return files().references()
                .filter(file -> file.getJobId() == null)
                .collect(toUnmodifiableList());
    }

    @Override
    public Stream<String> getReadyForGCFilenamesBefore(Instant maxUpdateTime) throws StateStoreException {
        return files().unreferencedBefore(maxUpdateTime);
    }

    @Override
    public boolean hasNoFiles() {
        return files().isEmpty();
    }

    @Override
    public void initialise() throws StateStoreException {
    }

    @Override
    public void splitFileReferences(List<SplitFileReferenceRequest> splitRequests) throws SplitRequestsFailedException {
    }

    private StateStoreFiles files() {
        StateStoreFiles files = new StateStoreFiles();
        logStore.readAllTransactions(FileTransaction.class)
                .forEach(transaction -> transaction.apply(files));
        return files;
    }

}
