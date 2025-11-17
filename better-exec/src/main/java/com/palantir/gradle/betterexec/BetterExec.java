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

import com.palantir.gradle.utils.circleciartifacts.ArtifactLocation;
import com.palantir.gradle.utils.circleciartifacts.CircleCiArtifacts;
import com.palantir.gradle.utils.environmentvariables.EnvironmentVariables;
import groovy.lang.Closure;
import java.util.stream.IntStream;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.TaskAction;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;

public abstract class BetterExec extends DefaultTask implements BetterExecCommon {

    private final SerializableOrSpec<String> retryWhen = SerializableOrSpec.empty();

    @Inject
    protected abstract WorkerExecutor getWorkerExecutor();

    @Nested
    protected abstract CircleCiArtifacts getCircleCiArtifacts();

    @Nested
    protected abstract EnvironmentVariables getEnvVarsFromUtils();

    @Internal
    protected abstract Property<ArtifactLocation> getArtifactLocation();

    public BetterExec() {
        getArtifactLocation().set(findAvailableLocation(getProject().getName() + "." + getName()));
        getArtifactLocation().finalizeValueOnRead();

        getWorkingDir().set(".");

        getCircleLogFilePath()
                .fileProvider(getArtifactLocation()
                        .map(ArtifactLocation::physicalPath)
                        .map(RegularFile::getAsFile));

        getShowRealTimeLogs().set(isOnCi().map(isOnCi -> !isOnCi));
        getCheckExitStatus().set(true);
        getMaxRetries().set(getProject().provider(() -> retryWhen.isEmpty() ? 1 : 5));
    }

    @TaskAction
    public final void exec() {
        WorkQueue workQueue = getWorkerExecutor().noIsolation();

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
            params.getIsOnCi().set(isOnCi());
            params.getCircleArtifactsUrlLocation()
                    .set(getArtifactLocation().map(ArtifactLocation::circleLink).orElse(""));
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
    public final void retryWhen(@SuppressWarnings("for-rollout:RawTypes") Closure _closure) {
        throw new UnsupportedOperationException("Groovy closures are not supported by retryWhen. "
                + "Please use `retryWhenOutputContains 'substring'` from Gradle groovy scripts. "
                + "A Gradle implementation detail required to make this task run in parallel means that predicates for "
                + "retryWhen need to be Serializable, which Closures made form Gradle groovy scripts cannot be.");
    }

    public final void retryWhenOutputContains(String substring) {
        retryWhen(output -> output.contains(substring));
    }

    private Provider<Boolean> isOnCi() {
        return getEnvVarsFromUtils()
                .envVarOrFromTestingProperty("CI")
                .map(_value -> true)
                .orElse(false);
    }

    private Provider<ArtifactLocation> findAvailableLocation(String baseName) {
        return getCircleCiArtifacts().resolveArtifactLocation(baseName + ".log").map(location -> {
            if (!location.physicalPath().getAsFile().exists()) {
                return location;
            }

            return IntStream.iterate(2, i -> i + 1)
                    .mapToObj(i -> {
                        String fileName = baseName + "." + i + ".log";
                        return getCircleCiArtifacts()
                                .resolveArtifactLocation(fileName)
                                .get();
                    })
                    .filter(loc -> !loc.physicalPath().getAsFile().exists())
                    .findFirst()
                    .get();
        });
    }
}
