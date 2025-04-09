<p align=right>
<a href=https://autorelease.palantir.build/repos/gradle-plugins/better-exec><img src=https://shields.palantir.build/badge/Perform%20an-Autorelease-brightgreen.svg alt=Autorelease></a>
</p>

# better-exec


An improved version of the built in [`Exec`](https://docs.gradle.org/8.2/javadoc/org/gradle/api/tasks/Exec.html) task or calling [`Project#exec`](https://docs.gradle.org/8.2/javadoc/org/gradle/api/Project.html#exec-org.gradle.api.Action-)/[`ExecOperations#exec`](https://docs.gradle.org/8.2/javadoc/org/gradle/process/ExecOperations.html#exec-org.gradle.api.Action-) inside a custom task.

In particular, it provides:

* **Better error messages** when the process fails, including the stdout/stderr of the process, both in the exception message and logged to the console the moment the process fails. No more wondering what happened with `Process 'command <command>' finished with non-zero exit value 1`. Integrates with [gradle-failure-reports](https://github.com/palantir/gradle-failure-reports) to show the Gradle errors as failure reports in Circle Ci.
* **Logs stdout/stderr** of the process to `$CIRCLE_ARTIFACTS` as it runs, reducing memory buffering when the process succeeds, and providing logs in the event the daemon dies. Logs for successful runs are stored as circle artifacts, so when investigating an issue as a Gradle Plugin maintainer, you can see what happened.
* **Logs errors ASAP** in a long-running build. If you are watching a long build on CI to see if something is going to fail, this will show you the error message immediately in the console output rather than having to wait until the end of the build in an exception message.
* **Fully parallelisable**, meaning it will run in parallel with other parallelisable tasks in the same project. Without some effort, custom tasks are not parallelisable - this takes that bit of hard work out of the equation for you.
* **Retries** are implemented, meaning if some external tool is flaky, you can retry a configurable number of times based on the contents of the output.

## Usage

The maven coordinate you should add to `build.gradle`s is:

```
com.palantir.gradle.better-exec:better-exec
```

Put this version in `versions.props`:

```
com.palantir.gradle.better-exec:* = <latest version>
```

The recommended way of using this task is to extend it and add your own inputs/outputs, configuring the `BetterExec` task in the constructor. With your own inputs you can wire up the outputs of your plugin's tasks to inputs easier and avoid using `dependsOn`:

```java
import com.palantir.gradle.betterexec.BetterExec;
import java.util.List;

abstract class CopyFileWithCp extends BetterExec {
    @InputFile
    public abstract RegularFileProperty getInput();

    @OutputFile
    public abstract RegularFileProperty getOutput();

    public MyExec() {
        // Provide the command to actually run.
        // Use the lazy version as the task hasn't run yet and 
        //   input/output haven't been set yet.
        getCommand().addAll(getProject.provider(() -> {
            return List.of(
                    "cp",
                    getInput().getAsFile().get().toString(),
                    getOutput().getAsFile().get().toString());
        }));
        
        // The working directory the command runs in
        getWorkingDir().set(getProject.getProjectDir());
        
        // Add environment variables
        // When using an input property, you want to use the
        //   lazy provider methods.
        getEnvironment().put("KEY", "value");
        
        // Does not retry by default, but if you can change that:
        retryWhenOutputContains("something flaky");
        retryWhenOutputContains("something else flaky");
        
        // If retries are enabled, it will retry 5 times by default.
        // However you can change it:
        getMaxRetries().set(10);
        
        // By default, it will fail if the exit code is not zero.
        // You can disable this behaviour:
        getCheckExitStatus().set(false);

        // No stdin in provided by default. But you can give a string:
        getStdin().set("stdin");

        // When something fails, you can give a brief description of what
        getCustomErrorMessage().set("SIREN SIREN SIREN");
        
        // Default is to print real time logs locally but not on CI.
        // You can disable it everywhere like so.
        // I'd recommend not enabling this on CI as it will be noisy.
        getShowRealTimeLogs().set(false);
    }
} 
```

You can also use the task directly, but this may make wiring up to other tasks in your plugin harder, as well as making a very large `Plugin` class, so is not recommended:

```java
getTasks().register('copyFileWithCp', BetterExec.class, copyFileWithCp -> {
    copyFileWithCp.getCommand().set(/* ... */);
});
```

## Further background

### The main problem

When using the regular `Exec` task, when the process fails it produces the extremely unhelpful error message:

```
Process 'command <command>' finished with non-zero exit value 1
```

If you are maintaining a Gradle plugin, this is extremely frustrating - there is no stderr or output or anything that can help you diagnose what has gone wrong. You end up having to run this again yourself.

### Bad solutions

Some common approaches to rectify this leave much to be desired:

**Make a custom task which calls `Project#exec` or `ExecOperations#exec` but save the output for later:**

```java
@TaskAction
public final void action() {
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    ExecResult execResult = getProject().exec(execSpec -> {
        execSpec.setStandardOutput(output);
        execSpec.setErrorOutput(output);
        execSpec.setIgnoreExitCode(true);
        // ...
    });

    if (execResult.getExitValue() != 0) {
        throw new RuntimeException(
                "Command ... failed with exit code " + execResult.getExitValue() 
                + ". Output: " + output);
    }
}
```

This has numerous downsides:

* The task is not parallelisable by using the [Worker Api](https://docs.gradle.org/current/userguide/worker_api.html). This means that this task will not be able to run in parallel with any other tasks in that project. Even if it is short lived task, it will stall/hold up other tasks.
* The task is not configuration cacheable.
* Buffers the entire output into memory, even when not needed. Could cause memory issues.
* In a long running build you must wait until the end to see the actual error.
* Lots of boilerplate to write every time.

**Print out stdout/stderr in the `Exec` task:**

```java
project.getTasks().register('myExec', Exec.class, exec -> {
    exec.setStandardOutput(System.out);
    exec.setErrorOutput(System.err);
    // ...
});
```

or variants of this, such as using a `LoggingOutputStream` to log to a logger.

This has numerous downsides:

* The output is not in the exception, users have to scroll up to find it. Output is also not in the build scan.
* The output is printed out to the console _even if the task succeeds_, which is very noisy.
* Have to remember to do this every time.

## Better solution

Use this library as listed above!
