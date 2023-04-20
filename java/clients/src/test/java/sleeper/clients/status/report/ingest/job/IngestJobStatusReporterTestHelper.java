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

package sleeper.clients.status.report.ingest.job;

import sleeper.clients.status.report.StatusReporterTestHelper;
import sleeper.clients.status.report.job.query.JobQuery;
import sleeper.clients.testutil.ToStringPrintStream;
import sleeper.ingest.job.status.IngestJobStatus;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static sleeper.clients.status.report.ingest.job.IngestJobStatusReporterTestData.ingestMessageCount;

public class IngestJobStatusReporterTestHelper {
    private IngestJobStatusReporterTestHelper() {
    }

    public static String replaceBracketedJobIds(List<IngestJobStatus> job, String example) {
        return StatusReporterTestHelper.replaceBracketedJobIds(job.stream()
                .map(IngestJobStatus::getJobId)
                .collect(Collectors.toList()), example);
    }

    public static String getStandardReport(JobQuery.Type query, List<IngestJobStatus> statusList, int numberInQueue) {
        return getStandardReport(query, statusList, numberInQueue, Collections.emptyMap());
    }

    public static String getStandardReport(JobQuery.Type query, List<IngestJobStatus> statusList, int numberInQueue,
                                           Map<String, Integer> persistentEmrStepCount) {
        ToStringPrintStream output = new ToStringPrintStream();
        new StandardIngestJobStatusReporter(output.getPrintStream()).report(statusList, query,
                ingestMessageCount(numberInQueue), persistentEmrStepCount);
        return output.toString();
    }

    public static String getJsonReport(JobQuery.Type query, List<IngestJobStatus> statusList, int numberInQueue) {
        return getJsonReport(query, statusList, numberInQueue, Collections.emptyMap());
    }

    public static String getJsonReport(JobQuery.Type query, List<IngestJobStatus> statusList, int numberInQueue,
                                       Map<String, Integer> persistentEmrStepCount) {
        ToStringPrintStream output = new ToStringPrintStream();
        new JsonIngestJobStatusReporter(output.getPrintStream()).report(statusList, query,
                ingestMessageCount(numberInQueue), persistentEmrStepCount);
        return output.toString();
    }
}
