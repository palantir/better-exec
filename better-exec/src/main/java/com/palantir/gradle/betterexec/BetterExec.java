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

import groovy.lang.Closure;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.TaskAction;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;

public abstract class BetterExec extends DefaultTask implements BetterExecCommon {

    private final SerializableOrSpec<String> retryWhen = SerializableOrSpec.empty();

    @Inject
    protected abstract WorkerExecutor getWorkerExecutor();

    public BetterExec() {
        getWorkingDir().set(".");

        getCircleLogFilePath()
                .fileProvider(getProject().provider(() -> EnvironmentVariables.envVarOrFromTestingProperty(
                                getProject(), "CIRCLE_ARTIFACTS")
                        .map(circleArtifacts -> Stream.concat(
                                        Stream.of(""),
                                        IntStream.iterate(2, i -> i + 1).mapToObj(i -> "." + i))
                                .map(extra -> new File(
                                        circleArtifacts, getProject().getName() + "." + getName() + extra + ".log"))
                                .filter(file -> !file.exists())
                                .findFirst()
                                .get())
                        .orElse(null)));

        getShowRealTimeLogs().set(!isOnCi());
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
            params.getCircleArtifactsUrlLocation().set(circleArtifactsLogFileLocation());
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

    private boolean isOnCi() {
        return EnvironmentVariables.envVarOrFromTestingProperty(getProject(), "CI")
                .isPresent();
    }

    private String circleArtifactsLogFileLocation() {
        Optional<String> circleWorkflowJobId =
                EnvironmentVariables.envVarOrFromTestingProperty(getProject(), "CIRCLE_WORKFLOW_JOB_ID");
        Optional<String> circleNodeIndex =
                EnvironmentVariables.envVarOrFromTestingProperty(getProject(), "CIRCLE_NODE_INDEX");

        if (!isOnCi()
                || circleWorkflowJobId.isEmpty()
                || circleNodeIndex.isEmpty()
                || !getCircleLogFilePath().isPresent()) {
            return "";
        }

        String circleHome = EnvironmentVariables.envVarOrFromTestingProperty(getProject(), "CIRCLE_HOME_DIRECTORY")
                .orElse("/home/circleci/");

        String circleUrl = EnvironmentVariables.envVarOrFromTestingProperty(getProject(), "CIRCLE_BUILD_URL")
                .map(BetterExec::extractDomain)
                .orElse("<circle_url>");
        return String.format(
                "See output at: https://%s/output/job/%s/artifacts/%s",
                circleUrl,
                circleWorkflowJobId.get(),
                circleNodeIndex.get()
                        + getCircleLogFilePath().getAsFile().get().toString().replace(circleHome, "/~/"));
    }

    public static String extractDomain(String url) {
        try {
            URL urlObj = new URL(url);
            return urlObj.getProtocol() + "://" + urlObj.getHost();
        } catch (MalformedURLException e) {
            return "Invalid URL";
        }
    }
}
