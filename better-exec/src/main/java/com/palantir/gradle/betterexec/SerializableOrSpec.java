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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

final class SerializableOrSpec<T> implements Serializable {
    private static final long serialVersionUID = -4082552991316846311L;

    private final List<SerializablePredicate<T>> predicates = new ArrayList<>();

    private SerializableOrSpec() {}

    public SerializableOrSpec<T> or(SerializablePredicate<T> predicate) {
        predicates.add(predicate);
        return this;
    }

    public boolean isEmpty() {
        return predicates.isEmpty();
    }

    public boolean isSatisfiedBy(T value) {
        return predicates.stream().anyMatch(predicate -> predicate.test(value));
    }

    public static <T> SerializableOrSpec<T> empty() {
        return new SerializableOrSpec<>();
    }
}
