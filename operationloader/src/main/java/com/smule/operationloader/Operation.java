/*
 * Copyright (C) 2016 Smule, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.smule.operationloader;

import android.support.annotation.NonNull;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

/**
 * An Operation represents a distinct task to execute prior to, or in response to,
 * a set of dependencies.
 */
public class Operation {
    public static final int NORMAL_PRIORITY = 0;

    private static final String[] EMPTY_DEPENDENCIES = new String[0];

    public final String name;
    public final String[] dependencies;

    /* package */ boolean mSuccess;

    /* package */ int mPriority;

    /* package */ volatile boolean mExecuting = false;
    /* package */ volatile boolean mExecuted = false;
    /* package */ volatile long mLastTimeExecuted = 0;
    /* package */ volatile long mStartTime;

    /**
     * Constructs an Operation with a given name and set of dependencies with the default priority
     *
     * @param name Name to associate with the Operation in the dependency graph
     * @param dependencies Names of Operations this Operation depends upon
     */
    public Operation(final String name, final String[] dependencies) {
        this(name, dependencies, NORMAL_PRIORITY);
    }

    /**
     * Constructs an Operation with a given name, set of dependencies, and priority
     *
     * @param name Name to associate with the Operation in the dependency graph
     * @param dependencies Names of Operations this Operation depends upon
     * @param priority Priority of this Operation compared to others; larger values indicate higher priority
     */
    public Operation(final String name, final String[] dependencies, final int priority) {
        this.name = name;
        this.dependencies = dependencies != null ? dependencies : EMPTY_DEPENDENCIES;
        mPriority = priority;
    }

    @Deprecated
    public void load(OperationLoader loader) {
        // There wasn't a success/fail before so simply report success
        done(true);
    }

    /**
     * Method involked when all the dependencies for this Operation have been satisfied
     * @param statuses List of completion statuses for this Operations dependencies
     */
    protected void onReady(@NonNull final List<OperationStatus> statuses) {
        // Continue to support the old way
        load(mOperationLoader);
    }

    /**
     * Method involked in order to report completion of the code associated with this
     * Operation.
     *
     * @param success {@code true} if the Operation was successfully executed, {@code false} otherwise
     */
    protected final void done(final boolean success) {
        mSuccess = success;

        mExecuted = true;
        mExecuting = false;
        mLastTimeExecuted = System.currentTimeMillis();

        mOperationLoader.operationComplete(this);
    }

    /* package */ OperationLoader mOperationLoader;

    /* package */ void reset() {
        mExecuting = false;
        mExecuted = false;
        mLastTimeExecuted = 0;
    }

    /**
     * Status of an Operation; reported to {@code onReady} to indicate the OperationStatuses for
     * an Operation's dependencies.
     */
    public static class OperationStatus {
        public final String name;
        public final boolean success;

        /* package */ OperationStatus(final String name, final boolean success) {
            this.name = name;
            this.success = success;
        }
    }
}