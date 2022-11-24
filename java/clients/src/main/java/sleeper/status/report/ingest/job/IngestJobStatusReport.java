/*
 * Copyright 2022 Crown Copyright
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

package sleeper.status.report.ingest.job;

import sleeper.core.record.process.AverageRecordRate;
import sleeper.core.record.process.status.ProcessRun;
import sleeper.ingest.job.status.IngestJobStatus;
import sleeper.status.report.StandardProcessStatusReporter;
import sleeper.status.report.table.TableField;
import sleeper.status.report.table.TableRow;
import sleeper.status.report.table.TableWriter;
import sleeper.status.report.table.TableWriterFactory;

import java.io.PrintStream;
import java.util.List;

import static sleeper.status.report.StandardProcessStatusReporter.formatDecimal;

public class IngestJobStatusReport {

    private final TableField stateField;
    private final TableField jobIdField;
    private final TableField totalFilesField;
    private final TableWriterFactory tableFactory;
    private final StandardProcessStatusReporter standardProcessStatusReporter;

    private final PrintStream out;

    public IngestJobStatusReport(PrintStream out) {
        this.out = out;
        TableWriterFactory.Builder tableFactoryBuilder = TableWriterFactory.builder();
        stateField = tableFactoryBuilder.addField("STATE");
        jobIdField = tableFactoryBuilder.addField("JOB_ID");
        totalFilesField = tableFactoryBuilder.fieldBuilder("TOTAL_FILES").alignRight().build();
        standardProcessStatusReporter = new StandardProcessStatusReporter(tableFactoryBuilder);
        tableFactory = tableFactoryBuilder.build();
    }

    public void run(IngestJobQuery query, List<IngestJobStatus> statusList, int numberInQueue) {
        out.println();
        out.println("Ingest Job Status Report");
        out.println("------------------------");
        out.printf("Total jobs waiting in queue (excluded from report): %s%n", numberInQueue);
        out.printf("Total jobs in progress: %s%n", statusList.stream().filter(status -> !status.isFinished()).count());
        out.printf("Total jobs finished: %s%n", statusList.stream().filter(IngestJobStatus::isFinished).count());
        printAverageIngestRate("Average ingest rate: %s%n", statusList);
        tableFactory.tableBuilder()
                .itemsAndSplittingWriter(statusList, this::writeJob)
                .build().write(out);
    }

    private void printAverageIngestRate(String formatString, List<IngestJobStatus> jobs) {
        AverageRecordRate average = recordRate(jobs);
        if (average.getJobCount() < 1) {
            return;
        }
        String rateString = String.format("%s read/s, %s write/s",
                formatDecimal(average.getRecordsReadPerSecond()),
                formatDecimal(average.getRecordsWrittenPerSecond()));
        out.printf(formatString, rateString);
    }

    private static AverageRecordRate recordRate(List<IngestJobStatus> jobs) {
        return AverageRecordRate.of(jobs.stream()
                .flatMap(job -> job.getJobRuns().stream())
                .filter(ProcessRun::isFinished)
                .map(ProcessRun::getFinishedSummary));
    }

    private void writeJob(IngestJobStatus job, TableWriter.Builder table) {
        job.getJobRuns().forEach(run -> table.row(row -> {
            writeJobFields(job, row);
            row.value(stateField, StandardProcessStatusReporter.getState(run));
            standardProcessStatusReporter.writeRunFields(run, row);
        }));

    }

    private void writeJobFields(IngestJobStatus job, TableRow.Builder builder) {
        builder.value(jobIdField, job.getJobId())
                .value(totalFilesField, job.getInputFileCount());
    }
}
