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

import static com.palantir.gradle.testing.assertion.GradlePluginTestAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.palantir.gradle.testing.execution.GradleInvoker;
import com.palantir.gradle.testing.execution.InvocationResult;
import com.palantir.gradle.testing.files.arbitrary.ArbitraryFile;
import com.palantir.gradle.testing.junit.GradlePluginTests;
import com.palantir.gradle.testing.project.RootProject;
import com.palantir.platform.OperatingSystem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@GradlePluginTests
class BetterExecTest {

    @BeforeEach
    void setup(RootProject rootProject) {
        rootProject.settingsGradle().rootProjectName("project");

        rootProject
                .buildGradle()
                .prepend(
                        """
                        buildscript {
                            repositories {
                                mavenLocal()
                                mavenCentral()
                            }
                            dependencies {
                                classpath 'com.palantir.gradle.better-exec:better-exec:%1$s'
                                // Test fixtures jar contains RetryWhenOutputContainsFailure for testing custom predicates
                                classpath 'com.palantir.gradle.better-exec:better-exec:%1$s:test-fixtures'
                            }
                        }

                        import com.palantir.gradle.betterexec.BetterExec
                        """,
                        Optional.ofNullable(System.getProperty("projectVersion"))
                                .orElseThrow());

        rootProject
                .gradlePropertiesFile()
                .setProperty("__TESTING", "true")
                .setProperty("__TESTING_CI", "true")
                .setProperty("__TESTING_CIRCLE_ARTIFACTS", rootProject.path() + "/circle-artifacts")
                .setProperty("__TESTING_CIRCLE_HOME_DIRECTORY", rootProject.path() + "/")
                .setProperty("__TESTING_CIRCLE_WORKFLOW_JOB_ID", "de700126-0f58-4624-aed3-1cdd297ed785")
                .setProperty("__TESTING_CIRCLE_NODE_INDEX", "2")
                .setProperty("__TESTING_CIRCLE_BUILD_URL", "https://mycircle.url/gh/palantir/better-exec/1581")
                .setProperty("__TESTING_CIRCLE_PROJECT_USERNAME", "foo")
                .setProperty("__TESTING_CIRCLE_PROJECT_REPONAME", "bar")
                .setProperty("__TESTING_CIRCLE_BUILD_NUM", "1");
    }

    ArbitraryFile circleArtifactsLog(RootProject rootProject, String taskName) {
        return rootProject.file("circle-artifacts/project." + taskName + ".log");
    }

    @Test
    void passing_better_exec_should_output_to_log_file(GradleInvoker gradle, RootProject rootProject) {
        rootProject.directory("subdir").createDirectories();

        rootProject.buildGradle().append("""
            task foo(type: BetterExec) {
                command = ['sh', '-c', 'echo "Hello I am in: ${PWD##*/}, also: $FOO"']
                workingDir = 'subdir'
                environment.put 'FOO', 'bar'
            }
            """);

        gradle.withArgs("foo").buildsSuccessfully();

        circleArtifactsLog(rootProject, "foo").assertThat().hasContent("Hello I am in: subdir, also: bar\n");
    }

    @Test
    void outputs_to_custom_log_file_location(GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().append("""
            task foo(type: BetterExec) {
                command = ['sh', '-c', 'echo Hello']
                circleLogFilePath = file('output.log')
            }
            """);

        gradle.withArgs("foo").buildsSuccessfully();

        rootProject.file("output.log").assertThat().hasContent("Hello\n");
    }

    @Test
    void when_task_is_run_over_multiple_gradle_invocations_the_output_log_makes_a_new_file_each_time(
            GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().append("""
            task foo(type: BetterExec) {
                command = ['sh', '-c', 'echo Hello']
            }
            """);

        gradle.withArgs("foo").buildsSuccessfully();
        gradle.withArgs("foo").buildsSuccessfully();

        circleArtifactsLog(rootProject, "foo").assertThat().hasContent("Hello\n");
        circleArtifactsLog(rootProject, "foo.2").assertThat().hasContent("Hello\n");
    }

    @Test
    void prints_the_custom_error_message(GradleInvoker gradle, RootProject rootProject) {
        rootProject.directory("subdir").createDirectories();

        rootProject.buildGradle().append("""
            task foo(type: BetterExec) {
                command = ['sh', '-c', 'exit 1']
                customErrorMessage = 'This is a custom error message'
            }
            """);

        InvocationResult executionResult = gradle.withArgs("foo").buildsWithFailure();

        assertThat(executionResult).output().contains("This is a custom error message");
    }

    @ParameterizedTest(name = "showRealTimeLogs_{0}")
    @ValueSource(strings = {"true", "false"})
    void failing_better_exec_should_include_logs_in_output_when_show_real_time_logs(
            String shouldShowRealTimeLogs, GradleInvoker gradle, RootProject rootProject) {
        rootProject.directory("subdir").createDirectories();

        rootProject.buildGradle().append("""
            task foo(type: BetterExec) {
                command = ['sh', '-c', 'echo OH NO! && exit 4']
                workingDir = 'subdir'
                showRealTimeLogs = %s
            }
            """, shouldShowRealTimeLogs);

        InvocationResult result = gradle.withArgs("foo").buildsWithFailure();

        // When showRealTimeLogs=true, "OH NO!" appears BEFORE "Task failed" (streamed in real-time)
        // When showRealTimeLogs=false, "OH NO!" only appears AFTER in the "Output:" section
        String output = result.output();
        int ohNoIndex = output.indexOf("OH NO!");
        int taskFailedIndex = output.indexOf("Task failed after 1 attempts");
        if (shouldShowRealTimeLogs.equals("true")) {
            assertThat(ohNoIndex).isLessThan(taskFailedIndex);
        } else {
            assertThat(ohNoIndex).isGreaterThan(taskFailedIndex);
        }

        String executable = OperatingSystem.get() == OperatingSystem.MACOS ? "/bin/sh" : "sh";
        String expectedOutput = """
            Output:

            OH NO!

            Command: [%s, -c, echo OH NO! && exit 4]
            Working dir: subdir\
            """.formatted(executable);

        assertThat(result)
                .output()
                .contains("Task failed after 1 attempts with exit code 4.")
                .contains(
                        "https://mycircle.url/output/job/de700126-0f58-4624-aed3-1cdd297ed785/artifacts/2/~/circle-artifacts/project.foo.log")
                .contains(expectedOutput)
                .contains("Task failed after 1 attempts with exit code 4.");
    }

    @Test
    void uses_full_path_for_command(GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().append("""
            task foo(type: BetterExec) {
                command = ['docker', 'test']
            }
            """);

        InvocationResult result = gradle.withArgs("foo").buildsWithFailure();

        assertThat(result).output().contains("docker: 'test' is not a docker command.");
        if (OperatingSystem.get() == OperatingSystem.MACOS) {
            assertThat(result).output().contains("Command: [/usr/local/bin/docker, test]");
        } else {
            assertThat(result).output().contains("Command: [docker, test]");
        }
    }

    @Test
    void uses_original_command_line_if_command_is_relative_to_the_working_dir(
            GradleInvoker gradle, RootProject rootProject) throws IOException {
        rootProject.file("subdir/script").append("""
            #!/bin/sh
            env | grep FOO
            """);

        rootProject.buildGradle().append("""
            task foo(type: BetterExec) {
                command = ['./script']
                workingDir = 'subdir'
                environment.put("FOO", "this is my foo text")
            }
            """);

        Files.setPosixFilePermissions(
                rootProject.file("subdir/script").path(),
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));

        gradle.withArgs("foo").buildsSuccessfully();

        circleArtifactsLog(rootProject, "foo").assertThat().hasContent("FOO=this is my foo text\n");
    }

    @Test
    void fails_after_exceeding_max_retries(GradleInvoker gradle, RootProject rootProject) {
        rootProject.directory("subdir").createDirectories();

        rootProject.buildGradle().append("""
            task foo(type: BetterExec) {
                command = ['sh', '-c', 'echo error && exit 255']
                workingDir = 'subdir'
                retryWhenOutputContains 'error'
                maxRetries = 3
            }
            """);

        InvocationResult result = gradle.withArgs("foo").buildsWithFailure();

        assertThat(result).output().contains("Task failed after 4 attempts with exit code 255.");
    }

    @Test
    void retries_when_there_is_a_matching_error_based_on_some_predicate(GradleInvoker gradle, RootProject rootProject) {
        rootProject.directory("subdir").createDirectories();

        rootProject.buildGradle().append("""
            import com.palantir.gradle.betterexec.RetryWhenOutputContainsFailure

            task foo(type: BetterExec) {
                def i = 0
                command = provider {
                    ['bash', '-c', '[ -f counter ] || echo 1 >counter; if [[ "$(cat counter)" == 2 ]]; then echo Success; else echo Failure; expr "$(cat counter)" + 1 >counter; exit 1; fi']
                }
                workingDir = 'subdir'
                // We must use a java created type here as the Groovy closures/lambdas end up not Serializable
                retryWhen(new RetryWhenOutputContainsFailure())
                maxRetries = 1
            }
            """);

        gradle.withArgs("foo").buildsSuccessfully();

        circleArtifactsLog(rootProject, "foo")
                .assertThat()
                .content()
                .contains("Failure")
                .contains("Retrying after 1 attempt(s) as output matches retryWhen");
    }

    @Test
    void retries_when_there_is_a_matching_error_based_on_retry_when_output_contains(
            GradleInvoker gradle, RootProject rootProject) {
        rootProject.directory("subdir").createDirectories();

        rootProject.buildGradle().append("""
            task foo(type: BetterExec) {
                def i = 0
                command = provider {
                    ['bash', '-c', '[ -f counter ] || echo 1 >counter; if [[ "$(cat counter)" == 2 ]]; then echo Success; else echo Failure; expr "$(cat counter)" + 1 >counter; exit 1; fi']
                }
                workingDir = 'subdir'
                retryWhenOutputContains 'Failure'
                maxRetries = 1
            }
            """);

        gradle.withArgs("foo").buildsSuccessfully();

        circleArtifactsLog(rootProject, "foo")
                .assertThat()
                .content()
                .contains("Failure")
                .contains("Retrying after 1 attempt(s) as output matches retryWhen");
    }

    @Test
    void throws_a_nice_error_when_you_try_to_use_a_closure_in_retry_when(
            GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().append("""
            task foo(type: BetterExec) {
                retryWhen { output -> true}
            }
            """);

        InvocationResult failure = gradle.withArgs("foo").buildsWithFailure();

        assertThat(failure).output().contains("Groovy closures are not supported");
    }

    @Test
    void doesnt_explode_when_if_not_all_the_correct_env_vars_are_set_for_circle_artifacts_link(
            GradleInvoker gradle, RootProject rootProject) {
        rootProject.gradlePropertiesFile().overwrite("""
            __TESTING=true
            __TESTING_CI=true
            """);

        rootProject.buildGradle().append("""
            task foo(type: BetterExec) {
                command = ['false']
            }
            """);

        InvocationResult failure = gradle.withArgs("foo").buildsWithFailure();

        assertThat(failure).output().contains("Task failed after 1 attempts");
    }

    @Test
    void the_circle_log_file_is_not_an_output(GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().append("""
            task foo(type: BetterExec) {
                command = ['true']
            }

            def fooOutputFiles = tasks.foo.outputs.files.files
            task printOutputs {
                doFirst {
                    println "foo outputs: ${fooOutputFiles}"
                }
            }
            """);

        InvocationResult result = gradle.withArgs("printOutputs").buildsSuccessfully();

        assertThat(result).output().contains("foo outputs: []");
    }

    @Test
    @Timeout(20)
    void runs_in_parallel_in_the_same_project(GradleInvoker gradle, RootProject rootProject) {
        rootProject.buildGradle().append("""
            task foo(type: BetterExec) {
                command = ['sh', '-c', 'echo true >foo-ready && while [ ! -e bar-ready ]; do sleep 1; done']
            }

            task bar(type: BetterExec) {
                command = ['sh', '-c', 'echo true >bar-ready && while [ ! -e foo-ready ]; do sleep 1; done']
            }
            """);

        gradle.withArgs("foo", "bar", "--parallel").buildsSuccessfully();
    }
}
