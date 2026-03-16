/*
 * (c) Copyright 2021 Palantir Technologies Inc. All rights reserved.
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
package com.palantir.gradle.betterexec;

import com.google.errorprone.annotations.RestrictedApi;
import groovy.lang.Closure;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.TaskAction;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;

public abstract class BetterExec extends DefaultTask implements BetterExecCommon {

    private final SerializableOrSpec<String> retryWhen = SerializableOrSpec.empty();
    private final BetterExecInternals internals;

    @Inject
    @RestrictedApi(
            explanation = "Do not use — internal to BetterExec",
            allowedOnPath = ".*/com/palantir/gradle/betterexec/.*")
    protected abstract WorkerExecutor getBetterExecInternalWorkerExecutor();

    public BetterExec() {
        internals =
                new BetterExecInternals(getProject().getObjects(), getProject().getName() + "." + getName());

        getWorkingDir().set(".");

        getCircleLogFilePath().fileProvider(internals.circleLogFile());

        getShowRealTimeLogs().set(internals.isOnCi().map(isOnCi -> !isOnCi));
        getCheckExitStatus().set(true);
        getMaxRetries().set(getProject().provider(() -> retryWhen.isEmpty() ? 1 : 5));
    }

    @TaskAction
    public final void exec() {
        WorkQueue workQueue = getBetterExecInternalWorkerExecutor().noIsolation();

        workQueue.submit(BetterExecAction.class, params -> {
            params.getCommand().set(getCommand());
            params.getWorkingDir().set(getWorkingDir());
            params.getEnvironment().set(getEnvironment());
            params.getCustomErrorMessage().set(getCustomErrorMessage());
            params.getStdin().set(getStdin());
            params.getShowRealTimeLogs().set(getShowRealTimeLogs());
            params.getCheckExitStatus().set(getCheckExitStatus());
            params.getCircleLogFilePath().set(getCircleLogFilePath());
            params.getMaxRetries().set(getMaxRetries());
            params.getShouldIncludeStacktraceForFailure().set(getShouldIncludeStacktraceForFailure());

            params.getRetryWhen().set(retryWhen);
            params.getIsOnCi().set(internals.isOnCi());
            params.getCircleArtifactsUrlLocation().set(internals.circleArtifactsUrl());
        });
    }

    public final void retryWhen(SerializablePredicate<String> outputMatcher) {
        retryWhen.or(outputMatcher);
    }

    /**
     * Will always throw.
     * @deprecated Groovy closures are not supported by retryWhen. Please use `retryWhenOutputContains 'substring'`
     *             from Gradle groovy scripts.
     * */
    @Deprecated
    @SuppressWarnings({"DoNotCallSuggester", "rawtypes"})
    public final void retryWhen(Closure _closure) {
        throw new UnsupportedOperationException("Groovy closures are not supported by retryWhen. "
                + "Please use `retryWhenOutputContains 'substring'` from Gradle groovy scripts. "
                + "A Gradle implementation detail required to make this task run in parallel means that predicates for "
                + "retryWhen need to be Serializable, which Closures made form Gradle groovy scripts cannot be.");
    }

    public final void retryWhenOutputContains(String substring) {
        retryWhen(output -> output.contains(substring));
    }
}
