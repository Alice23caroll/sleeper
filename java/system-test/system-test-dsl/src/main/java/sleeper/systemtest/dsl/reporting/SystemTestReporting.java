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

package sleeper.systemtest.dsl.reporting;

import sleeper.systemtest.dsl.SystemTestContext;
import sleeper.systemtest.dsl.SystemTestDrivers;

public class SystemTestReporting {

    private final ReportingContext context;
    private final IngestReportsDriver ingestDriver;
    private final CompactionReportsDriver compactionDriver;

    public SystemTestReporting(SystemTestContext context) {
        SystemTestDrivers drivers = context.instance().adminDrivers();
        this.context = context.reporting();
        this.ingestDriver = drivers.ingestReports(context);
        this.compactionDriver = drivers.compactionReports(context);
    }

    public SystemTestIngestJobsReport ingestJobs() {
        return new SystemTestIngestJobsReport(ingestDriver.jobs(context));
    }

    public SystemTestCompactionJobsReport compactionJobs() {
        return new SystemTestCompactionJobsReport(compactionDriver.jobs(context));
    }
}
