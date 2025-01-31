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
package sleeper.core.statestore.inmemory;

import sleeper.core.statestore.AllReferencesToAFile;
import sleeper.core.statestore.AllReferencesToAllFiles;
import sleeper.core.statestore.AssignJobIdRequest;
import sleeper.core.statestore.FileReference;
import sleeper.core.statestore.FileReferenceStore;
import sleeper.core.statestore.SplitFileReferenceRequest;
import sleeper.core.statestore.SplitRequestsFailedException;
import sleeper.core.statestore.StateStoreException;
import sleeper.core.statestore.exception.FileAlreadyExistsException;
import sleeper.core.statestore.exception.FileHasReferencesException;
import sleeper.core.statestore.exception.FileNotFoundException;
import sleeper.core.statestore.exception.FileReferenceAlreadyExistsException;
import sleeper.core.statestore.exception.FileReferenceAssignedToJobException;
import sleeper.core.statestore.exception.FileReferenceNotAssignedToJobException;
import sleeper.core.statestore.exception.FileReferenceNotFoundException;
import sleeper.core.statestore.exception.NewReferenceSameAsOldReferenceException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toUnmodifiableList;
import static sleeper.core.statestore.AllReferencesToAFile.fileWithOneReference;

public class InMemoryFileReferenceStore implements FileReferenceStore {

    private final Map<String, AllReferencesToAFile> filesByFilename = new TreeMap<>();
    private Clock clock = Clock.systemUTC();

    @Override
    public void addFilesWithReferences(List<AllReferencesToAFile> files) throws StateStoreException {
        Instant updateTime = clock.instant();
        for (AllReferencesToAFile file : files) {
            if (filesByFilename.containsKey(file.getFilename())) {
                throw new FileAlreadyExistsException(file.getFilename());
            }
            filesByFilename.put(file.getFilename(), file.withCreatedUpdateTime(updateTime));
        }
    }

    @Override
    public List<FileReference> getFileReferences() {
        return streamFileReferences().collect(toUnmodifiableList());
    }

    @Override
    public Stream<String> getReadyForGCFilenamesBefore(Instant maxUpdateTime) {
        List<String> filenames = filesByFilename.values().stream()
                .filter(file -> file.getTotalReferenceCount() < 1)
                .filter(file -> file.getLastStateStoreUpdateTime().isBefore(maxUpdateTime))
                .map(AllReferencesToAFile::getFilename)
                .collect(toUnmodifiableList());
        return filenames.stream();
    }

    @Override
    public List<FileReference> getFileReferencesWithNoJobId() {
        return streamFileReferences()
                .filter(file -> file.getJobId() == null)
                .collect(toUnmodifiableList());
    }

    @Override
    public Map<String, List<String>> getPartitionToReferencedFilesMap() {
        return streamFileReferences().collect(
                groupingBy(FileReference::getPartitionId,
                        mapping(FileReference::getFilename, toList())));
    }

    @Override
    public void splitFileReferences(List<SplitFileReferenceRequest> splitRequests) throws SplitRequestsFailedException {
        Instant updateTime = clock.instant();
        int firstUnappliedRequestIndex = 0;
        for (SplitFileReferenceRequest splitRequest : splitRequests) {
            AllReferencesToAFile file = filesByFilename.get(splitRequest.getFilename());
            Optional<StateStoreException> failure = validateSplitRequest(splitRequest, file);
            if (failure.isPresent()) {
                throw splitRequestsFailed(splitRequests, firstUnappliedRequestIndex, failure.get());
            }

            filesByFilename.put(splitRequest.getFilename(),
                    file.splitReferenceFromPartition(
                            splitRequest.getFromPartitionId(),
                            splitRequest.getNewReferences(),
                            updateTime));
            firstUnappliedRequestIndex++;
        }
    }

    private static Optional<StateStoreException> validateSplitRequest(
            SplitFileReferenceRequest request, AllReferencesToAFile existingFile) {
        if (existingFile == null) {
            return Optional.of(new FileNotFoundException(request.getFilename()));
        }
        FileReference oldReference = existingFile.getReferenceForPartitionId(request.getFromPartitionId()).orElse(null);
        if (oldReference == null) {
            return Optional.of(new FileReferenceNotFoundException(request.getFilename(), request.getFromPartitionId()));
        }
        if (oldReference.getJobId() != null) {
            return Optional.of(new FileReferenceAssignedToJobException(oldReference));
        }
        for (FileReference newReference : request.getNewReferences()) {
            FileReference existingReference = existingFile.getReferenceForPartitionId(newReference.getPartitionId()).orElse(null);
            if (existingReference != null) {
                return Optional.of(new FileReferenceAlreadyExistsException(existingReference));
            }
        }
        return Optional.empty();
    }

    private static SplitRequestsFailedException splitRequestsFailed(
            List<SplitFileReferenceRequest> splitRequests, int firstUnappliedRequestIndex, StateStoreException cause) {
        return new SplitRequestsFailedException(
                splitRequests.subList(0, firstUnappliedRequestIndex),
                splitRequests.subList(firstUnappliedRequestIndex, splitRequests.size()), cause);
    }

    @Override
    public void atomicallyReplaceFileReferencesWithNewOne(String jobId, String partitionId, List<String> inputFiles, FileReference newReference) throws StateStoreException {
        for (String filename : inputFiles) {
            AllReferencesToAFile file = filesByFilename.get(filename);
            if (file == null) {
                throw new FileNotFoundException(filename);
            }
            Optional<FileReference> referenceOpt = file.getInternalReferences().stream()
                    .filter(ref -> partitionId.equals(ref.getPartitionId())).findFirst();
            if (referenceOpt.isEmpty()) {
                throw new FileReferenceNotFoundException(filename, partitionId);
            }
            FileReference reference = referenceOpt.get();
            if (!jobId.equals(reference.getJobId())) {
                throw new FileReferenceNotAssignedToJobException(reference, jobId);
            }
            if (filename.equals(newReference.getFilename())) {
                throw new NewReferenceSameAsOldReferenceException(filename);
            }
        }
        if (filesByFilename.containsKey(newReference.getFilename())) {
            throw new FileAlreadyExistsException(newReference.getFilename());
        }

        Instant updateTime = clock.instant();
        for (String filename : inputFiles) {
            filesByFilename.put(filename, filesByFilename.get(filename)
                    .removeReferenceForPartition(partitionId, updateTime));
        }
        filesByFilename.put(newReference.getFilename(), fileWithOneReference(newReference, updateTime));
    }

    private Stream<FileReference> streamFileReferences() {
        return filesByFilename.values().stream()
                .flatMap(file -> file.getInternalReferences().stream());
    }

    @Override
    public void assignJobIds(List<AssignJobIdRequest> requests) throws StateStoreException {
        Instant updateTime = clock.instant();
        for (AssignJobIdRequest request : requests) {
            assignJobId(request, updateTime);
        }
    }

    private void assignJobId(AssignJobIdRequest request, Instant updateTime) throws StateStoreException {
        for (String filename : request.getFilenames()) {
            AllReferencesToAFile existingFile = filesByFilename.get(filename);
            if (existingFile == null) {
                throw new FileReferenceNotFoundException(filename, request.getPartitionId());
            }
            FileReference existingReference = existingFile.getReferenceForPartitionId(request.getPartitionId()).orElse(null);
            if (existingReference == null) {
                throw new FileReferenceNotFoundException(filename, request.getPartitionId());
            }
            if (existingReference.getJobId() != null) {
                throw new FileReferenceAssignedToJobException(existingReference);
            }
        }
        for (String filename : request.getFilenames()) {
            filesByFilename.put(filename, filesByFilename.get(filename)
                    .withJobIdForPartition(request.getJobId(), request.getPartitionId(), updateTime));
        }
    }

    @Override
    public void deleteGarbageCollectedFileReferenceCounts(List<String> filenames) throws StateStoreException {
        for (String filename : filenames) {
            AllReferencesToAFile file = filesByFilename.get(filename);
            if (file == null) {
                throw new FileNotFoundException(filename);
            } else if (file.getTotalReferenceCount() > 0) {
                throw new FileHasReferencesException(file);
            }
        }
        filenames.forEach(filesByFilename::remove);
    }

    @Override
    public AllReferencesToAllFiles getAllFilesWithMaxUnreferenced(int maxUnreferencedFiles) {
        List<AllReferencesToAFile> filesWithNoReferences = filesByFilename.values().stream()
                .filter(file -> file.getTotalReferenceCount() < 1)
                .collect(toUnmodifiableList());
        List<AllReferencesToAFile> files = Stream.concat(
                filesByFilename.values().stream()
                        .filter(file -> file.getTotalReferenceCount() > 0),
                filesWithNoReferences.stream().limit(maxUnreferencedFiles))
                .collect(toUnmodifiableList());
        return new AllReferencesToAllFiles(files, filesWithNoReferences.size() > maxUnreferencedFiles);
    }

    @Override
    public void initialise() {

    }

    @Override
    public boolean hasNoFiles() {
        return filesByFilename.isEmpty();
    }

    @Override
    public void clearFileData() {
        filesByFilename.clear();
    }

    @Override
    public void fixFileUpdateTime(Instant now) {
        clock = Clock.fixed(now, ZoneId.of("UTC"));
    }
}
