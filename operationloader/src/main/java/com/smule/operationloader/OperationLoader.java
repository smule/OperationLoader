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

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.support.annotation.NonNull;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An OperationLoader is a utillty for building a basic dependency graph. Operations are processed based on
 * priority and the order in which they are submitted.
 *
 * Consider the following dependency graph: Name>[Priority]: dependency, dependency
 * A[10]: C
 * B[10]: A, C
 * C[10]: none
 * D[10]: none
 *
 * In this case, since they all have the same priority, C would run first, then A, then B, then D, given the
 * submit order. This can be changed by setting priorities.
 *
 * A[10]: C
 * B[10]: A, C
 * C[10]: none
 * D[20]: none
 *
 * Now D would run first, followed by C, then A, and finally B.
 *
 */
public class OperationLoader {
    public static final String TAG = OperationLoader.class.getSimpleName();
    private static final boolean VERBOSE = false;

    private final Map<String, Operation> mOperations = new LinkedHashMap<>();
    private volatile boolean mHasUnexecuted;

    private Handler mWorkerThread;

    private final List<Operation> mRunlist = new ArrayList<>(10);

    private final Object mLock = new Object();

    /**
     * Adds an operation that's been implemented by a runnable.
     *
     * @param name The name of the operation to submit
     * @param dependencies The list of dependencies
     * @param load The runnable to execute
     * @return The OperationLoader instance that the Operation was added to
     */
    public OperationLoader addOperation(String name, String[] dependencies, final Runnable load) {
        final Operation newOp = new Operation(name, dependencies, Operation.NORMAL_PRIORITY) {
            @Override
            protected void onReady(@NonNull final List<OperationStatus> statuses) {
                load.run();
                done(true);
            }
        };
        return addOperation(newOp);
    }

    /**
     * Adds an Operation to the dependency graph
     * @param operation The Operation to add
     * @return The OperationLoader the Operation was added to
     */
    public OperationLoader addOperation(final Operation operation) {
        if (VERBOSE) {
            Log.d(TAG, "addOperation: " + operation.name);
        }

        // Associate this OperationLoader with the Operation
        operation.mOperationLoader = this;
        operation.reset();

        putOperation(operation.name, operation);
        return this;
    }

    /**
     * Removes an operation from the dependency graph
     * @param name The name of the operation to remove
     * @return The Operation that was removed or {@code null} if the Operation was not found
     */
    public Operation removeOperation(String name) {
        final Operation operation;
        synchronized (mLock) {
            operation = mOperations.remove(name);
        }
        operationRemoved(operation);
        return operation;
    }

    /**
     * Causes an Operation to be executed again
     *
     * @param name The name of the Operation to trigger
     * @return {@code true} if the operation will be triggered again, {@code false} if the operation was not found OR
     * if the operation is currently executing
     */
    public boolean reTriggerOperation(String name) {
        synchronized (mLock) {
            final Operation op = mOperations.get(name);
            if (op == null) {
                return false;
            }
            if (op.mExecuting) {
                return false;
            }
            op.mExecuted = false;
            op.mLastTimeExecuted = 0;
        }

        if (VERBOSE) {
            Log.d(TAG, "reTriggerOperation: " + name);
        }

        exec();
        return true;
    }

    /**
     * Checks to see if an Operation has been executed
     * @param name Name of the operation to check
     * @return true if the operation has been executed, false if the operation was not found or has not executed yet
     */
    public boolean hasOperationBeenExecuted(String name) {
        synchronized (mLock) {
            Operation op = mOperations.get(name);
            return op != null && op.mExecuted;
        }
    }

    /**
     * Checks to see if a list of Operations have been executed
     * @param names Names of the operations to check
     * @return {@code true} if the operations have been executed, {@code false} if any of them have not been or if any of them
     * were not found.
     */
    public boolean haveOperationsBeenExecuted(String[] names) {
        for (String name : names) {
            if (!hasOperationBeenExecuted(name)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Waits until one or more dependencies are complete, and then invokes a Runnable.
     *
     * @param dependencies dependencies to wait on
     * @param onCompletion Runnable to invoke when all dependencies are satisfied
     */
    public void waitOnOperations(String[] dependencies, final Runnable onCompletion) {
        if (haveOperationsBeenExecuted(dependencies)) {
            onCompletion.run();
        } else {
            final String name = onCompletion.getClass().toString() + "@" + System.identityHashCode(onCompletion);
            addOperation(new Operation(name, dependencies) {
                @Override
                protected void onReady(@NonNull final List<OperationStatus> statuses) {
                    onCompletion.run();

                    removeOperation(name);
                    done(true);
                }
            }).exec();
        }
    }

    private void putOperation(String name, Operation op) {
        synchronized (mLock) {
            mOperations.put(name, op);
        }

        operationAdded(op);
        exec();
    }

    private synchronized void exec() {
        if (mWorkerThread == null) {
            /*
             * A Handler needs a thread with a Looper so create a Handler thread,
             * call .start() on it, and then create a Handler that wraps it.
             * Calling HandlerThread.getLooper() WILL BLOCK until the thread is up
             * and Looper.prepare() returns. Still, this is intentional.
             */
            final HandlerThread workerThread = new HandlerThread(TAG);
            workerThread.start();
            mWorkerThread = new Handler(workerThread.getLooper(), mExecCallback);
        }

        // Since the above will block we can definitely send a message to the thread now to start processing
        mWorkerThread.sendEmptyMessage(NEXT_MESSAGE);
    }

    /* Find the next operation to execute. */
    private Operation findNextOperation() {
        Operation foundOp = null;
        boolean hasUnexecuted = false;
        boolean hasExecuting = false;
        boolean isIncompleteGraph = false;
        synchronized (mLock) {
            mRunlist.clear();
            for (Operation op : mOperations.values()) {
                if (op.mExecuting) {
                    hasExecuting = true;
                    continue;
                }
                if (!op.mExecuted) {
                    hasUnexecuted = true;
                }
                boolean allExecuted = true;
                if (op.dependencies != null) {
                    for (String dep : op.dependencies) {
                        Operation operation = mOperations.get(dep);
                        if (operation == null) {
                            isIncompleteGraph = true;
                        }
                        if (operation == null || !operation.mExecuted) {
                            allExecuted = false;
                        }
                    }
                }

                if (!allExecuted) {
                    continue;
                }

                if (!op.mExecuted || needsUpdate(op)) {
                    mRunlist.add(op);
                }
            }

            // After collecting the list of runnable operations, pick the highest priority one
            if (!mRunlist.isEmpty()) {
                foundOp = mRunlist.get(0);
                for (int i = 1; i < mRunlist.size(); ++i) {
                    final Operation op = mRunlist.get(i);
                    if (op.mPriority > foundOp.mPriority) {
                        foundOp = op;
                    }
                }
            }
        }

        if (foundOp == null && hasUnexecuted && !hasExecuting && !isIncompleteGraph) {
            Log.e(TAG, "Problem choosing next operation to execute. Is there a dependency cycle?");
        }

        // Note whether we found unexecuted operations
        mHasUnexecuted = hasUnexecuted;

        return foundOp;
    }

    /**
     * Checks to see if an Operation should be run again (because one of its dependencies has been rerun)
     * @param op Operation to check
     * @return {@code true} if the Operation should be executed again, {@code false} otherwise
     */
    private boolean needsUpdate(Operation op) {
        if (op.dependencies != null) {
            for (String dep : op.dependencies) {
                Operation depOp = mOperations.get(dep);
                if (needsUpdate(depOp)) {
                    return false;
                }
                if (depOp.mLastTimeExecuted > op.mLastTimeExecuted) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final int NEXT_MESSAGE = 0x1877319b;
    private static final int OPERATION_ADDED = 0x28d902f1;
    private static final int OPERATION_REMOVED = 0xbeaa15c2;
    private static final int OPERATION_COMPLETE = 0x22d79819;
    private static final int WATCHDOG = 0x00dead00;

    private static final List<Operation.OperationStatus> EMPTY_STATUSES = new ArrayList<>(0);

    private final Handler.Callback mExecCallback = new Handler.Callback() {
        @Override
        public boolean handleMessage(final Message msg) {
            Operation foundOp;

            switch (msg.what) {
                case OPERATION_ADDED:
                    mHasUnexecuted = true;
                    foundOp = findNextOperation();
                    break;
                case OPERATION_COMPLETE:
                    foundOp = findNextOperation();
                    break;
                case OPERATION_REMOVED:
                    foundOp = findNextOperation();
                    break;
                case NEXT_MESSAGE:
                    foundOp = findNextOperation();
                    break;
                case WATCHDOG:
                    final Handler handler = msg.getTarget();
                    if (handler != null && mHasUnexecuted) {
                        handler.sendEmptyMessage(NEXT_MESSAGE);
                    }
                    return true;
                default:
                    return false;
            }

            if (foundOp == null) {
                if (VERBOSE) {
                    final StringBuilder builder = new StringBuilder("No Op Found to run; Pending Ops:\n");
                    int pendingOpCount = 0;
                    synchronized (mLock) {
                        for (final Operation operation : mOperations.values()) {
                            if (!operation.mExecuted && !operation.mExecuting) {
                                builder.append(operation.name).append(": ");
                                for (final String dep : operation.dependencies) {
                                    final Operation depOp = mOperations.get(dep);
                                    builder.append(dep).append((depOp != null && depOp.mExecuted) ? " " : "* ");
                                }
                                builder.append("\n");
                                ++pendingOpCount;
                            }
                        }
                    }
                    if (pendingOpCount > 0) {
                        Log.d(TAG, builder.toString());
                    }
                }

                return true;
            }
            foundOp.mExecuting = true;
            foundOp.mExecuted = false;

            if (VERBOSE) {
                foundOp.mStartTime = System.currentTimeMillis();
                Log.d(TAG, "OPERATION STARTING : " + foundOp.name);
            }

            foundOp.onReady(generateStatusesForOp(foundOp));

            // Set a watchdog timeout in case an operation doesn't complete
            final Handler handler = msg.getTarget();
            if (handler != null) {
                handler.removeMessages(WATCHDOG);

                // Set watchdog
                if (mHasUnexecuted) {
                    handler.sendEmptyMessageDelayed(WATCHDOG, 500);
                }

                // Loop in a moment
                handler.sendEmptyMessageDelayed(NEXT_MESSAGE, 100);
            }

            return true;
        }
    };

    private List<Operation.OperationStatus> generateStatusesForOp(final Operation operation) {
        if (operation.dependencies == null || operation.dependencies.length == 0) {
            return EMPTY_STATUSES;
        }

        final List<Operation.OperationStatus> statuses = new ArrayList<>();
        synchronized (mLock) {
            for (final String dep : operation.dependencies) {
                final Operation op = mOperations.get(dep);
                statuses.add(new Operation.OperationStatus(dep, op.mSuccess));
            }
        }
        return statuses;
    }

    @Deprecated
    public void operationDone(Operation op) {
        op.done(true);
    }

    private void operationAdded(final Operation operation) {
        if (mWorkerThread != null) {
            if (VERBOSE) {
                Log.d(TAG, "operationAdded: " + operation);
            }
            mWorkerThread.sendMessage(getOperationMessage(OPERATION_ADDED, operation));
        }
    }

    private void operationRemoved(final Operation operation) {
        if (mWorkerThread != null) {
            if (VERBOSE) {
                Log.d(TAG, "operationRemoved: " + operation);
            }
            mWorkerThread.sendMessage(getOperationMessage(OPERATION_REMOVED, operation));
        }
    }

    /* package */ void operationComplete(final Operation operation) {
        if (VERBOSE) {
            Log.d(TAG, "operationComplete: " + operation);
        }
        mWorkerThread.sendMessage(getOperationMessage(OPERATION_COMPLETE, operation));
    }

    private Message getOperationMessage(final int what, final Operation operation) {
        final Message message = Message.obtain();
        message.what = what;
        message.obj = operation;
        return message;
    }
}
