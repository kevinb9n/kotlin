/*
 * Copyright 2010-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.jet.storage;

import com.intellij.openapi.util.Computable;
import com.intellij.util.Consumer;
import com.intellij.util.Function;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jet.utils.WrappedValues;

public class StorageUtil {
    public static <T> NotNullLazyValue<T> createRecursionIntolerantLazyValueWithDefault(
            @NotNull final T defaultValue,
            @NotNull Computable<T> compute
    ) {
        //noinspection unchecked
        return LockBasedStorageManager.NO_LOCKS.createLazyValueWithPostCompute(
                compute,
                new Function<Boolean, Object>() {
                    @Override
                    public Object fun(Boolean firstTime) {
                        if (firstTime) return WrappedValues.escapeThrowable(new ReenteringLazyValueComputationException());
                        return defaultValue;
                    }
                },
                Consumer.EMPTY_CONSUMER
        );
    }
}
