/*
 * Copyright 2016, The Android Open Source Project
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
package co.daresay.things.doorbell;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;

import com.google.android.gms.iid.InstanceID;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";
    private static final String PREFS_KEY_REGISTERED = "PREFS_KEY_REGISTERED";

    private DatabaseReference mDatabaseRef;

    private RecyclerView mRecyclerView;
    private DoorbellEntryAdapter mAdapter;
    private String clientToken;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mRecyclerView = (RecyclerView) findViewById(R.id.doorbellView);
        // Show most recent items at the top
        LinearLayoutManager layoutManager =
                new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, true);
        mRecyclerView.setLayoutManager(layoutManager);

        // Reference for doorbell events from embedded device
        mDatabaseRef = FirebaseDatabase.getInstance().getReference().child("logs");

        clientToken = InstanceID.getInstance(this).getId();
        maybeRegisterToPremiumZone();
    }

    @SuppressLint("StaticFieldLeak")
    private void maybeRegisterToPremiumZone() {
        if (getPreferences(Context.MODE_PRIVATE).getBoolean(PREFS_KEY_REGISTERED, false)) {
            return;
        }
        new AsyncTask<Void, Void, Void>() {

            @Override
            protected Void doInBackground(Void... voids) {
                OkHttpClient client = new OkHttpClient();
                JSONObject token = new JSONObject();
                try {
                    token.put("token", clientToken);
                    RequestBody codeBody = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), token.toString());
                    String requestParams = "?apikey=" + getString(R.string.telia_zone_api_key);
                    Request codeRequest = new Request.Builder()
                            .url("https://api.premiumzone.com/v2/code" + requestParams)
                            .post(codeBody)
                            .build();
                    Response codeResponse = client.newCall(codeRequest).execute();

                    if (codeResponse.code() != 201) {
                        Log.i(TAG, String.format("Failed fetch code: %s %s", codeResponse.code(), codeResponse.message()));
                    } else {

                        String codeResponseBody = codeResponse.body().string();
                        RequestBody registerBody = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), codeResponseBody);
                        Request registerRequest = new Request.Builder()
                                .url("https://api.premiumzone.com/v2/register")
                                .post(registerBody)
                                .build();
                        Response registerResponse = client.newCall(registerRequest).execute();
                        if (registerResponse.code() == 200) {
                            Log.i(TAG, String.format("Successfully registered to Premium Zone with client token: %s", clientToken));
                            getPreferences(Context.MODE_PRIVATE).edit().putBoolean(PREFS_KEY_REGISTERED, true).apply();
                        } else {
                            Log.i(TAG, String.format("Failed register to Premium Zone: %s %s", registerResponse.code(), registerResponse.message()));
                        }
                    }

                } catch (IOException e) {
                    Log.d(TAG, "Error during networking", e);
                } catch (JSONException e) {
                    Log.d(TAG, "Error creating json", e);
                }
                return null;
            }
        }.execute();
    }

    @Override
    public void onStart() {
        super.onStart();

        mAdapter = new DoorbellEntryAdapter(this, mDatabaseRef);
        mRecyclerView.setAdapter(mAdapter);

        // Make sure new events are visible
        mAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onItemRangeChanged(int positionStart, int itemCount) {
                mRecyclerView.smoothScrollToPosition(mAdapter.getItemCount());
            }
        });
    }

    @Override
    public void onStop() {
        super.onStop();

        // Tear down Firebase listeners in adapter
        if (mAdapter != null) {
            mAdapter.cleanup();
            mAdapter = null;
        }
    }

}
