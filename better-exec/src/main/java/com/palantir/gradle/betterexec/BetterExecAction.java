/*
 * (c) Copyright 2022 Palantir Technologies Inc. All rights reserved.
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

import com.palantir.gradle.failurereports.exceptions.ExceptionWithLogs;
import com.palantir.platform.OperatingSystem;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import javax.inject.Inject;
import org.apache.tools.ant.util.TeeOutputStream;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecResult;
import org.gradle.workers.WorkAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

abstract class BetterExecAction implements WorkAction<BetterExecWorkParams> {
    private static final int INITIAL_ATTEMPT = 1;

    private static final Logger log = LoggerFactory.getLogger(BetterExecAction.class);

    // Required so Gradle can make the type with reflection, while still keeping the class package-private
    @SuppressWarnings("checkstyle:RedundantModifier")
    public BetterExecAction() {}

    @Inject
    protected abstract ExecOperations getExecOperations();

    @Override
    public final void execute() {
        Optional<File> outputLogFile = Optional.ofNullable(
                getParameters().getCircleLogFilePath().getAsFile().getOrNull());

        outputLogFile.ifPresent(file -> file.getParentFile().mkdirs());

        List<String> processedCommand =
                getProcessedCommandLineArgs(getParameters().getCommand().get());

        try (OutputStream logOutput = outputLogFile
                .<OutputStream>map(BetterExecAction::bufferedOutputStream)
                .orElseGet(OutputStream::nullOutputStream)) {
            int lastAttempt = getParameters().getMaxRetries().get() + INITIAL_ATTEMPT;
            for (int attempt = INITIAL_ATTEMPT; attempt <= lastAttempt; attempt++) {
                Result result = executeCommandOnce(processedCommand);

                logOutput.write(result.output.getBytes(StandardCharsets.UTF_8));

                if (result.successful()) {
                    return;
                }

                boolean notGoingToRetry = getParameters().getRetryWhen().get().isEmpty()
                        || !getParameters().getRetryWhen().get().isSatisfiedBy(result.output)
                        || attempt == lastAttempt;
                if (notGoingToRetry) {
                    String header = String.format(
                            Locale.ROOT,
                            "Task failed after %d attempts with exit code %d.\n%s",
                            attempt,
                            result.exitCode,
                            Optional.ofNullable(getParameters()
                                            .getCustomErrorMessage()
                                            .getOrNull())
                                    .orElse(""));

                    String output = String.join(
                            "\n",
                            "Output:\n\n" + result.output,
                            "Command: " + processedCommand,
                            "Working dir: " + getParameters().getWorkingDir().get(),
                            getParameters().getCircleArtifactsUrlLocation().get());

                    log.error("{}\n{}", header, output);
                    throw new ExceptionWithLogs(
                            header,
                            output,
                            getParameters()
                                    .getShouldIncludeStacktraceForFailure()
                                    .getOrElse(true));
                }

                String retryMessage = String.format(
                        Locale.ROOT, "\n\nRetrying after %d attempt(s) as output matches retryWhen", attempt);
                logOutput.write(retryMessage.getBytes(StandardCharsets.UTF_8));
                log.warn("{}", retryMessage);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed", e);
        }
    }

    private static BufferedOutputStream bufferedOutputStream(File file) {
        try {
            return new BufferedOutputStream(new FileOutputStream(file));
        } catch (FileNotFoundException e) {
            throw new RuntimeException("Could not find file " + file, e);
        }
    }

    private Result executeCommandOnce(List<String> processedCommand) {
        ByteArrayOutputStream inMemoryOutput = new ByteArrayOutputStream();
        OutputStream logOutput = getParameters().getShowRealTimeLogs().get()
                ? new TeeOutputStream(System.out, inMemoryOutput)
                : inMemoryOutput;
        ExecResult execResult = getExecOperations().exec(execSpec -> {
            execSpec.setIgnoreExitValue(true);
            execSpec.commandLine(processedCommand);
            execSpec.workingDir(getParameters().getWorkingDir());
            execSpec.environment(getParameters().getEnvironment().get());
            execSpec.setStandardOutput(logOutput);
            execSpec.setErrorOutput(logOutput);

            if (getParameters().getStdin().isPresent()) {
                execSpec.setStandardInput(new ByteArrayInputStream(
                        getParameters().getStdin().get().getBytes(StandardCharsets.UTF_8)));
            }
        });

        return new Result(execResult.getExitValue(), inMemoryOutput.toString(StandardCharsets.UTF_8));
    }

    /**
     * Workaround for https://github.com/gradle/gradle/issues/10483. If the command is not a relative/absolute path,
     * find the full path of the executable (using the PATH env var) and pass it to the execSpec.
     */
    private List<String> getProcessedCommandLineArgs(List<String> commandLineArgs) {
        if (!OperatingSystem.get().equals(OperatingSystem.MACOS)) {
            return commandLineArgs;
        }

        if (commandLineArgs.isEmpty()) {
            return commandLineArgs;
        }

        if (commandLineArgs.get(0).startsWith("./")
                || commandLineArgs.get(0).startsWith("../")
                || commandLineArgs.get(0).startsWith("/")) {
            return commandLineArgs;
        }

        String command = commandLineArgs.get(0);

        Optional<Path> commandPath = maybeGetCommandPath(command);
        return commandPath
                .map(path -> Stream.concat(
                                Stream.of(path.toAbsolutePath().toString()),
                                commandLineArgs.subList(1, commandLineArgs.size()).stream())
                        .toList())
                .orElse(commandLineArgs);
    }

    @SuppressWarnings("for-rollout:StreamFlatMapOptional")
    private Optional<Path> maybeGetCommandPath(String command) {
        return Stream.of(System.getenv("PATH").split(":"))
                .map(Paths::get)
                .filter(Files::isDirectory)
                .map(dir -> maybeGetCommandPath(dir, command))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    private static Optional<Path> maybeGetCommandPath(Path directory, String command) {
        try (Stream<Path> contents = Files.list(directory)) {
            return contents.filter(
                            executable -> executable.getFileName().toString().equals(command))
                    .findFirst();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private final class Result {
        private final int exitCode;
        private final String output;

        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        public boolean successful() {
            return !getParameters().getCheckExitStatus().get() || exitCode == 0;
        }
    }
}
