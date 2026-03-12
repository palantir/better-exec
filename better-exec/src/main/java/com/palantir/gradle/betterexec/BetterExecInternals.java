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
import java.io.File;
import java.util.stream.IntStream;
import org.gradle.api.file.RegularFile;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;

/**
 * Encapsulates CircleCI artifact and environment variable logic so that {@link BetterExec}'s bytecode does not
 * directly reference implementation types like {@link ArtifactLocation}, {@link CircleCiArtifacts}, or
 * {@link EnvironmentVariables}. This allows those dependencies to be {@code implementation} rather than {@code api},
 * since {@code validatePlugins} can load {@link BetterExec} without needing those types on the compile classpath.
 */
final class BetterExecInternals {

    private final CircleCiArtifacts circleCiArtifacts;
    private final EnvironmentVariables environmentVariables;

    private final Property<ArtifactLocation> artifactLocation;

    BetterExecInternals(ObjectFactory objectFactory, String baseName) {
        circleCiArtifacts = objectFactory.newInstance(CircleCiArtifacts.class);
        environmentVariables = objectFactory.newInstance(EnvironmentVariables.class);
        artifactLocation = objectFactory.property(ArtifactLocation.class);

        artifactLocation.set(findAvailableLocation(baseName));
        artifactLocation.finalizeValueOnRead();
    }

    Provider<File> circleLogFile() {
        return artifactLocation.map(ArtifactLocation::physicalPath).map(RegularFile::getAsFile);
    }

    Provider<Boolean> isOnCi() {
        return environmentVariables
                .envVarOrFromTestingProperty("CI")
                .map(_value -> true)
                .orElse(false);
    }

    Provider<String> circleArtifactsUrl() {
        return artifactLocation.map(ArtifactLocation::circleLink).orElse("");
    }

    private Provider<ArtifactLocation> findAvailableLocation(String baseName) {
        return circleCiArtifacts.resolveArtifactLocation(baseName + ".log").map(location -> {
            if (!location.physicalPath().getAsFile().exists()) {
                return location;
            }

            return IntStream.iterate(2, i -> i + 1)
                    .mapToObj(i -> {
                        String fileName = baseName + "." + i + ".log";
                        return circleCiArtifacts
                                .resolveArtifactLocation(fileName)
                                .get();
                    })
                    .filter(loc -> !loc.physicalPath().getAsFile().exists())
                    .findFirst()
                    .get();
        });
    }
}
