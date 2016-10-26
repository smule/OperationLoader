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

package com.smule.demos.operationloader;

import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;
import butterknife.BindView;
import butterknife.ButterKnife;
import com.smule.operationloader.Operation;
import com.smule.operationloader.OperationLoader;

import java.util.Collections;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    @BindView(R.id.toolbar) protected Toolbar mToolbar;
    @BindView(R.id.greeting) protected TextView mGreeting;

    @BindView(R.id.fab) protected FloatingActionButton mActionButton;

    private OperationLoader mOperationLoader = new OperationLoader();
    private String mUserName = "Joshua";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);

        setSupportActionBar(mToolbar);

        mActionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                        .setAction("Action", null).show();
            }
        });

        // This is a very contrived set of actions, but it's just to demonstrate the principal
        mOperationLoader = new OperationLoader();
        mOperationLoader.addOperation(new Operation("NETWORK_LOGIN", null) {
            @Override
            protected void onReady(@NonNull final List<OperationStatus> statuses) {
                // Just wait to simulate a very slow network
                final long waitUntil = System.currentTimeMillis() + 2000;
                while (System.currentTimeMillis() < waitUntil) {
                    // Busy wait...
                }

                // Yay! We logged in
                done(true);
            }
        });

        // We need to wait for the user to "login" to update the UI
        mOperationLoader.addOperation(new Operation("UPDATE_UI", Collections.singletonList("NETWORK_LOGIN")) {
            @Override
            protected void onReady(@NonNull final List<OperationStatus> statuses) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mGreeting.setText(getString(R.string.logged_in_welcome, mUserName));
                    }
                });

                // It's okay that the UI isn't updated yet, for this example at least. This could be done in the runnable
                // if we really wanted to ensure everything was completed before we marked this as done.
                done(true);
            }
        });

        // This operation also depends on the login, but is a higher priority so it will be run first.
        mOperationLoader.addOperation(new Operation("UPDATE_USERNAME", Collections.singletonList("NETWORK_LOGIN"), 10) {
            @Override
            protected void onReady(@NonNull final List<OperationStatus> statuses) {
                mUserName = "Sophia";

                // It's okay that the UI isn't updated yet, for this example at least. This could be done in the runnable
                // if we really wanted to ensure everything was completed before we marked this as done.
                done(true);
            }
        });
    }
}
