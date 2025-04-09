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

package com.palantir.gradle.betterexec

import com.palantir.platform.OperatingSystem
import nebula.test.IntegrationSpec
import spock.lang.Timeout
import spock.lang.Unroll

class BetterExecIntegTest extends IntegrationSpec {
    def setup() {
        settingsFile << '''
            rootProject.name = 'project'
        '''.stripIndent(true)

        buildFile << '''
            import com.palantir.gradle.betterexec.BetterExec
        '''.stripIndent(true)

        file('gradle.properties') << """
            __TESTING=true
            __TESTING_CI=true
            __TESTING_CIRCLE_ARTIFACTS=${projectDir}/circle-artifacts
            __TESTING_CIRCLE_HOME_DIRECTORY=${projectDir}/
            __TESTING_CIRCLE_WORKFLOW_JOB_ID=de700126-0f58-4624-aed3-1cdd297ed785
            __TESTING_CIRCLE_NODE_INDEX=2
        """.stripIndent(true)
    }

    def 'passing BetterExec should output to log file'() {
        new File(getProjectDir(), "subdir").mkdir()

        //language=gradle
        buildFile << '''
            task foo(type: BetterExec) {
                command = ['sh', '-c', 'echo "Hello I am in: ${PWD##*/}, also: $FOO"']
                workingDir = 'subdir'
                environment.put 'FOO', 'bar'
            }
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('foo')

        then:
        def output = circleArtifactsLogOutput('foo')

        output == 'Hello I am in: subdir, also: bar\n'
    }

    def 'outputs to custom log file location'() {
        //language=gradle
        buildFile << '''
            task foo(type: BetterExec) {
                command = ['sh', '-c', 'echo Hello']
                circleLogFilePath = file('output.log')
            }
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('foo')

        then:
        def output = new File(projectDir, 'output.log').text

        output == 'Hello\n'
    }

    def 'when task is run over multiple gradle invocations, the output log makes a new file each time'() {
        //language=gradle
        buildFile << '''
            task foo(type: BetterExec) {
                command = ['sh', '-c', 'echo Hello']
            }
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('foo')
        runTasksSuccessfully('foo')

        then:
        def output1 = circleArtifactsLogOutput('foo')
        def output2 = circleArtifactsLogOutput('foo.2')

        output1 == 'Hello\n'
        output2 == 'Hello\n'
    }

    def 'prints the custom error message'() {
        new File(getProjectDir(), "subdir").mkdir()

        //language=gradle
        buildFile << '''
            task foo(type: BetterExec) {
                command = ['sh', '-c', 'exit 1']
                customErrorMessage = 'This is a custom error message'
            }
        '''.stripIndent(true)

        when:
        def executionResult = runTasksWithFailure('foo')
        println executionResult.standardError

        def failureMessage = executionResult.failure.cause.cause.cause.message

        then:
        failureMessage.contains('This is a custom error message')
    }

    @Unroll
    def 'failing BetterExec should include logs in output when showRealTimeLogs=#shouldShowRealTimeLogs'() {
        new File(getProjectDir(), "subdir").mkdir()
        def showRealTimeLogs = shouldShowRealTimeLogs
        //language=gradle
        buildFile << '''
            task foo(type: BetterExec) {
                command = ['sh', '-c', 'echo OH NO! && exit 4']
                workingDir = 'subdir'
                showRealTimeLogs = FLAG
            }
        '''.replace("FLAG", showRealTimeLogs).stripIndent(true)


        when:
        def result = runTasksWithFailure('foo')
        def failureMessage = result.failure.cause.cause.cause.message

        then:
        if (showRealTimeLogs) {
            result.standardOutput.contains('OH NO!')
        } else {
            !result.standardOutput.contains('OH NO!')
        }
        result.standardError.contains('Task failed after 1 attempts with exit code 4.')
        result.standardError.contains('https://circle.palantir.build/output/job/de700126-0f58-4624-aed3-1cdd297ed785/artifacts/2/~/circle-artifacts/project.foo.log')

        String executable = OperatingSystem.get() == OperatingSystem.MACOS ? "/bin/sh" : "sh"
        result.standardError.contains('''
            Output:

            OH NO!
            
            Command: [EXEC, -c, echo OH NO! && exit 4]
            Working dir: subdir'''.replace("EXEC", executable).stripIndent(true))
        failureMessage.contains 'Task failed after 1 attempts with exit code 4.'

        where:
        shouldShowRealTimeLogs << ["true", "false"]
    }

    def 'uses full path for command'() {
        buildFile << '''
            task foo(type: BetterExec) {
                command = ['docker', 'test']
            }
        '''.stripIndent(true)

        when:
        def result = runTasksWithFailure('foo')

        then:
        result.standardError.contains("docker: 'test' is not a docker command.")
        if (OperatingSystem.get() == OperatingSystem.MACOS) {
            result.standardError.contains("Command: [/usr/local/bin/docker, test]")
        } else {
            result.standardError.contains("Command: [docker, test]")
        }

    }

    def 'uses original commandLine if command is relative to the workingDir'() {
        new File(getProjectDir(), "subdir").mkdir()
        File script = new File(getProjectDir(), "subdir/script")

        script.write( """
            #!/bin/sh
            env | grep FOO
        """.stripIndent(true))

        //language=gradle
        buildFile << '''
            task foo(type: BetterExec) {
                command = ['./script']
                workingDir = 'subdir'
                environment.put("FOO", "this is my foo text")
            }
        '''.stripIndent(true)
        script.setExecutable(true)


        when:
        runTasksSuccessfully('foo')

        then:
        def output = circleArtifactsLogOutput('foo')

        output == 'FOO=this is my foo text\n'
    }

    def 'fails after exceeding maxRetries'() {
        new File(getProjectDir(), "subdir").mkdir()

        //language=gradle
        buildFile << '''
            task foo(type: BetterExec) {
                command = ['sh', '-c', 'echo error && exit 255']
                workingDir = 'subdir'
                retryWhenOutputContains 'error'
                maxRetries = 3
            }
        '''.stripIndent(true)

        when:
        def failureMessage = runTasksWithFailure('foo').failure.cause.cause.cause.message

        then:
        failureMessage.contains 'Task failed after 4 attempts with exit code 255.'
    }

    def 'retries when there is a matching error based on some predicate'() {
        new File(getProjectDir(), "subdir").mkdir()

        //language=gradle
        buildFile << '''
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
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('foo')

        then:
        def output = circleArtifactsLogOutput('foo')

        output.contains("Failure")
        output.contains("Retrying after 1 attempt(s) as output matches retryWhen")
    }

    def 'retries when there is a matching error based on retryWhenOutputContains'() {
        new File(getProjectDir(), "subdir").mkdir()

        //language=gradle
        buildFile << '''
            task foo(type: BetterExec) {
                def i = 0
                command = provider {
                    ['bash', '-c', '[ -f counter ] || echo 1 >counter; if [[ "$(cat counter)" == 2 ]]; then echo Success; else echo Failure; expr "$(cat counter)" + 1 >counter; exit 1; fi']
                }
                workingDir = 'subdir'
                retryWhenOutputContains 'Failure'
                maxRetries = 1
            }
        '''.stripIndent(true)

        when:
        runTasksSuccessfully('foo')

        then:
        def output = circleArtifactsLogOutput('foo')

        output.contains("Failure")
        output.contains("Retrying after 1 attempt(s) as output matches retryWhen")
    }

    def 'throws a nice error when you try to use a closure in retryWhen'() {
        // language=gradle
        buildFile << '''
            task foo(type: BetterExec) {
                retryWhen { output -> true}
            }
        '''.stripIndent(true)

        when:
        def failure = runTasksWithFailure('foo').failure.cause.cause.message

        then:
        failure.contains 'Groovy closures are not supported'
    }

    def 'doesnt explode when if not all the correct env vars are set for circle artifacts link'() {
        file('gradle.properties').text = '''
            __TESTING=true
            __TESTING_CI=true
        '''.stripIndent(true)

        // language=gradle
        buildFile << '''
            task foo(type: BetterExec) {
                command = ['false']
            }
        '''.stripIndent(true)

        when:
        def failure = runTasksWithFailure('foo').failure.cause.cause.cause.message

        then:
        failure.contains 'Task failed after 1 attempts'
    }

    def 'the circle log file is not an output'() {
        // language=gradle
        buildFile << '''
            task foo(type: BetterExec) {
                command = ['true']
            }
            
            task printOutputs {
                doFirst {
                    println "foo outputs: ${tasks.foo.outputs.files.files}"
                }
            }
        '''.stripIndent(true)

        when:
        def stdout = runTasksSuccessfully('printOutputs').standardOutput

        then:
        stdout.contains 'foo outputs: []'
    }

    @Timeout(20)
    def 'runs in parallel in the same project'() {
        when:
        // language=gradle
        buildFile << '''
            task foo(type: BetterExec) {
                command = ['sh', '-c', 'echo true >foo-ready && while [ ! -e bar-ready ]; do sleep 1; done']
            }

            task bar(type: BetterExec) {
                command = ['sh', '-c', 'echo true >bar-ready && while [ ! -e foo-ready ]; do sleep 1; done']
            }
        '''.stripIndent(true)

        then:
        runTasksSuccessfully('foo', 'bar', '--parallel')
    }

    String circleArtifactsLogOutput(String taskName) {
        return new File(projectDir, "circle-artifacts/project.${taskName}.log").text
    }
}
