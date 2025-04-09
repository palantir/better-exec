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

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;

interface BetterExecCommon {
    @Input
    ListProperty<String> getCommand();

    @Input
    Property<Object> getWorkingDir();

    @Input
    MapProperty<String, String> getEnvironment();

    @Input
    @Optional
    Property<String> getCustomErrorMessage();

    @Input
    @Optional
    Property<String> getStdin();

    @Input
    Property<Boolean> getShowRealTimeLogs();

    @Input
    Property<Boolean> getCheckExitStatus();

    @Internal
    @Optional
    RegularFileProperty getCircleLogFilePath();

    @Internal
    Property<Integer> getMaxRetries();

    @Internal
    @Optional
    Property<Boolean> getShouldIncludeStacktraceForFailure();
}
