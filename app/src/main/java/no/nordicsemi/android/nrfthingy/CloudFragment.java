/*
 * Copyright (c) 2010 - 2017, Nordic Semiconductor ASA
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form, except as embedded into a Nordic
 *    Semiconductor ASA integrated circuit in a product or a software update for
 *    such product, must reproduce the above copyright notice, this list of
 *    conditions and the following disclaimer in the documentation and/or other
 *    materials provided with the distribution.
 *
 * 3. Neither the name of Nordic Semiconductor ASA nor the names of its
 *    contributors may be used to endorse or promote products derived from this
 *    software without specific prior written permission.
 *
 * 4. This software, with or without modification, must only be used with a
 *    Nordic Semiconductor ASA integrated circuit.
 *
 * 5. Any software provided in binary form under this license must not be reverse
 *    engineered, decompiled, modified and/or disassembled.
 *
 * THIS SOFTWARE IS PROVIDED BY NORDIC SEMICONDUCTOR ASA "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY, NONINFRINGEMENT, AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL NORDIC SEMICONDUCTOR ASA OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
 * OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package no.nordicsemi.android.nrfthingy;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.renderscript.ScriptGroup;
import android.support.v4.app.Fragment;
import android.support.v7.widget.Toolbar;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.DecimalFormat;

import no.nordicsemi.android.nrfthingy.common.CloudGuideActivity;
import no.nordicsemi.android.nrfthingy.common.MessageDialogFragment;
import no.nordicsemi.android.nrfthingy.common.Utils;
import no.nordicsemi.android.nrfthingy.configuration.IFTTTokenDialogFragment;
import no.nordicsemi.android.nrfthingy.database.DatabaseContract.CloudDbColumns;
import no.nordicsemi.android.nrfthingy.database.DatabaseHelper;
import no.nordicsemi.android.thingylib.ThingyListener;
import no.nordicsemi.android.thingylib.ThingyListenerHelper;
import no.nordicsemi.android.thingylib.ThingySdkManager;
import no.nordicsemi.android.thingylib.utils.ThingyUtils;

public class CloudFragment extends Fragment implements IFTTTokenDialogFragment.IFTTTokenDialogFragmentListener {
    private static final int TEMPERATURE_UPDATE_EVENT = 0;
    private static final int PRESSURE_UPDATE_EVENT = 1;
    private static final int BUTTON_STATE_UPDATE_EVENT = 2;

    private Switch mTemperatureSwitch;
    private Switch mPressureSwitch;
    private Switch mButtonStateSwitch;

    private TextView mTemperatureView;
    private TextView mPressureView;
    private TextView mButtonStateView;
    private TextView mTemperatureIntervalView;
    private TextView mPressureIntervalView;
    private TextView mCloudTokenView;
    private TextView mUploadedView;
    private TextView mDownloadedView;

    private long mUploadedSize;
    private long mDownloadedSize;

    private String mCloudToken;
    private boolean mIsFragmentAttached = false;

    private CloudFragmentListener mListener;
    private BluetoothDevice mDevice;
    private ThingySdkManager mThingySdkManager;
    private static final DecimalFormat TIME_FORMAT = new DecimalFormat("#0.00");

    private DatabaseHelper mDatabaseHelper;
    private Handler mHandler = new Handler();

    private ThingyListener mThingyListener = new ThingyListener() {
        private long mButtonPressedTime;
        private long mButtonReleasedTime;

        @Override
        public void onDeviceConnected(BluetoothDevice device, int connectionState) {
            //Connectivity callbacks handled by main activity
        }

        @Override
        public void onDeviceDisconnected(BluetoothDevice device, int connectionState) {
            //Connectivity callbacks handled by main activity
        }

        @Override
        public void onServiceDiscoveryCompleted(BluetoothDevice device) {
            if (device.equals(mDevice)) {
                updateUi();
            }
        }

        @Override
        public void onTemperatureValueChangedEvent(BluetoothDevice bluetoothDevice, String temperature) {
            if (mIsFragmentAttached) {
                final String temp = (temperature) + "\u2103";
                mTemperatureView.setText(temp);
                if (mDatabaseHelper.getTemperatureUploadState(bluetoothDevice.getAddress())) {
                    uploadData(TEMPERATURE_UPDATE_EVENT, createTemperatureJson(temperature));
                }
            }
        }

        @Override
        public void onPressureValueChangedEvent(BluetoothDevice bluetoothDevice, final String pressure) {
            if (mIsFragmentAttached) {
                mPressureView.setText(getString(R.string.hecto_pascal, pressure));
                if (mDatabaseHelper.getPressureUploadState(bluetoothDevice.getAddress())) {
                    uploadData(PRESSURE_UPDATE_EVENT, createPressureJson(pressure));
                }
            }
        }

        @Override
        public void onHumidityValueChangedEvent(BluetoothDevice bluetoothDevice, final String humidity) {
        }

        @Override
        public void onAirQualityValueChangedEvent(BluetoothDevice bluetoothDevice, final int eco2, final int tvoc) {
        }

        @Override
        public void onColorIntensityValueChangedEvent(BluetoothDevice bluetoothDevice, final float red, final float green, final float blue, final float alpha) {
        }

        @Override
        public void onButtonStateChangedEvent(BluetoothDevice bluetoothDevice, int buttonState) {
            if (mIsFragmentAttached) {
                switch (buttonState) {
                    case ThingyUtils.BUTTON_STATE_PRESSED:
                        mButtonPressedTime = System.currentTimeMillis();
                        mButtonStateView.setText(R.string.button_state_pressed);
                        break;
                    case ThingyUtils.BUTTON_STATE_RELEASED:
                        mButtonReleasedTime = System.currentTimeMillis();
                        final float duration = (float) (mButtonReleasedTime - mButtonPressedTime) / 1000;
                        mButtonStateView.setText(getString(R.string.button_state_released) + "(" + duration + " sec)");
                        if (mDatabaseHelper.getButtonUploadState(bluetoothDevice.getAddress())) {
                            uploadData(BUTTON_STATE_UPDATE_EVENT, createButtonStateJson(duration));
                        }
                        break;
                }
            }
        }

        @Override
        public void onTapValueChangedEvent(BluetoothDevice bluetoothDevice, int direction, int count) {

        }

        @Override
        public void onOrientationValueChangedEvent(BluetoothDevice bluetoothDevice, int orientation) {

        }

        @Override
        public void onQuaternionValueChangedEvent(BluetoothDevice bluetoothDevice, float w, float x, float y, float z) {

        }

        @Override
        public void onPedometerValueChangedEvent(BluetoothDevice bluetoothDevice, int steps, long duration) {

        }

        @Override
        public void onAccelerometerValueChangedEvent(BluetoothDevice bluetoothDevice, float x, float y, float z) {

        }

        @Override
        public void onGyroscopeValueChangedEvent(BluetoothDevice bluetoothDevice, float x, float y, float z) {

        }

        @Override
        public void onCompassValueChangedEvent(BluetoothDevice bluetoothDevice, float x, float y, float z) {

        }

        @Override
        public void onEulerAngleChangedEvent(BluetoothDevice bluetoothDevice, float roll, float pitch, float yaw) {

        }

        @Override
        public void onRotationMatixValueChangedEvent(BluetoothDevice bluetoothDevice, byte[] matrix) {

        }

        @Override
        public void onHeadingValueChangedEvent(BluetoothDevice bluetoothDevice, float heading) {

        }

        @Override
        public void onGravityVectorChangedEvent(BluetoothDevice bluetoothDevice, float x, float y, float z) {

        }

        @Override
        public void onSpeakerStatusValueChangedEvent(BluetoothDevice bluetoothDevice, int status) {

        }

        @Override
        public void onMicrophoneValueChangedEvent(BluetoothDevice bluetoothDevice, final byte[] data) {

        }
    };

    public interface CloudFragmentListener {
        void OnCloudFragmentListener(final BluetoothDevice device);
    }

    public CloudFragment() {
        // Required empty public constructor
    }

    public static CloudFragment newInstance(final BluetoothDevice device) {
        CloudFragment fragment = new CloudFragment();
        final Bundle args = new Bundle();
        args.putParcelable(Utils.CURRENT_DEVICE, device);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            mDevice = getArguments().getParcelable(Utils.CURRENT_DEVICE);
        }
        mThingySdkManager = ThingySdkManager.getInstance();
        mDatabaseHelper = new DatabaseHelper(getActivity());

    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {

        View rootView = inflater.inflate(R.layout.fragment_cloud, container, false);

        final Toolbar toolbarFeatureControl = rootView.findViewById(R.id.card_toolbar_feature_control);
        toolbarFeatureControl.setLogo(R.drawable.ic_remote);
        toolbarFeatureControl.setTitle(R.string.feature_control);
        toolbarFeatureControl.inflateMenu(R.menu.menu_cloud_info);

        toolbarFeatureControl.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                final int id = item.getItemId();
                switch (id) {
                    case R.id.action_info:
                        Intent intent = new Intent(getActivity(), CloudGuideActivity.class);
                        startActivity(intent);
                        break;
                    case R.id.action_ifttt_token:
                        IFTTTokenDialogFragment ifttTokenDialogFragment = IFTTTokenDialogFragment.newInstance();
                        ifttTokenDialogFragment.show(getChildFragmentManager(), null);
                        break;
                }
                return false;
            }
        });

        final Toolbar toolbarFeatureInfo = rootView.findViewById(R.id.card_toolbar_feature_info);
        toolbarFeatureInfo.setLogo(R.drawable.ic_info_grey);
        toolbarFeatureInfo.setTitle(R.string.freature_info);

        final Toolbar toolbarDataStatistics = rootView.findViewById(R.id.card_toolbar_data_statistics);
        toolbarDataStatistics.setLogo(R.drawable.ic_data_usage);
        toolbarDataStatistics.setTitle(R.string.data_statistics);

        mTemperatureView = rootView.findViewById(R.id.temperature);
        mPressureView = rootView.findViewById(R.id.pressure);
        mButtonStateView = rootView.findViewById(R.id.button_state);
        mTemperatureIntervalView = rootView.findViewById(R.id.temperature_interval);
        mPressureIntervalView = rootView.findViewById(R.id.pressure_interval);
        mCloudTokenView = rootView.findViewById(R.id.cloud_token);
        mUploadedView = rootView.findViewById(R.id.uploaded);
        mDownloadedView = rootView.findViewById(R.id.downloaded);

        mTemperatureSwitch = rootView.findViewById(R.id.switch_temperature);
        mPressureSwitch = rootView.findViewById(R.id.switch_pressuure);
        mButtonStateSwitch = rootView.findViewById(R.id.switch_button);

        mTemperatureSwitch.setChecked(mDatabaseHelper.getTemperatureUploadState(mDevice.getAddress()) && !Utils.getIFTTTToken(getContext()).isEmpty());
        mTemperatureSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (!isChecked) {
                    mTemperatureView.setText(R.string.dash);
                    mDatabaseHelper.enableCloudNotifications(mDevice.getAddress(), isChecked, CloudDbColumns.COLUMN_TEMPERATURE_UPLOAD);
                    mThingySdkManager.enableTemperatureNotifications(mDevice, isChecked);
                    return;
                }
                final String iftttToken = Utils.getIFTTTToken(getContext());
                if (iftttToken.isEmpty()) {
                    IFTTTokenDialogFragment ifttTokenDialogFragment = IFTTTokenDialogFragment.newInstance();
                    ifttTokenDialogFragment.show(getChildFragmentManager(), null);
                } else {
                    mThingySdkManager.enableTemperatureNotifications(mDevice, isChecked);
                    mDatabaseHelper.enableCloudNotifications(mDevice.getAddress(), isChecked, CloudDbColumns.COLUMN_TEMPERATURE_UPLOAD);
                }
            }
        });

        mPressureSwitch.setChecked(mDatabaseHelper.getPressureUploadState(mDevice.getAddress()) && !Utils.getIFTTTToken(getContext()).isEmpty());
        mPressureSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (!isChecked) {
                    mPressureView.setText(R.string.dash);
                    mDatabaseHelper.enableCloudNotifications(mDevice.getAddress(), isChecked, CloudDbColumns.COLUMN_PRESSURE_UPLOAD);
                    mThingySdkManager.enablePressureNotifications(mDevice, isChecked);
                    return;
                }
                final String iftttToken = Utils.getIFTTTToken(getContext());
                if (iftttToken.isEmpty()) {
                    IFTTTokenDialogFragment ifttTokenDialogFragment = IFTTTokenDialogFragment.newInstance();
                    ifttTokenDialogFragment.show(getChildFragmentManager(), null);
                } else {
                    mThingySdkManager.enablePressureNotifications(mDevice, isChecked);
                    mDatabaseHelper.enableCloudNotifications(mDevice.getAddress(), isChecked, CloudDbColumns.COLUMN_PRESSURE_UPLOAD);
                }
            }
        });

        mButtonStateSwitch.setChecked(mDatabaseHelper.getButtonUploadState(mDevice.getAddress()) && !Utils.getIFTTTToken(getContext()).isEmpty());
        mButtonStateSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (!isChecked) {
                    mDatabaseHelper.enableCloudNotifications(mDevice.getAddress(), isChecked, CloudDbColumns.COLUMN_BUTTON_STATE_UPLOAD);
                    mThingySdkManager.enableButtonStateNotification(mDevice, isChecked);
                    return;
                }
                final String iftttToken = Utils.getIFTTTToken(getContext());
                if (iftttToken.isEmpty()) {
                    IFTTTokenDialogFragment ifttTokenDialogFragment = IFTTTokenDialogFragment.newInstance();
                    ifttTokenDialogFragment.show(getChildFragmentManager(), null);
                } else {
                    mThingySdkManager.enableButtonStateNotification(mDevice, isChecked);
                    mDatabaseHelper.enableCloudNotifications(mDevice.getAddress(), isChecked, CloudDbColumns.COLUMN_BUTTON_STATE_UPLOAD);
                }
            }
        });


        if (savedInstanceState != null) {
            mUploadedSize = savedInstanceState.getLong("UPLOADED");
            mDownloadedSize = savedInstanceState.getLong("DOWNLOADED");
        }


        ThingyListenerHelper.registerThingyListener(getContext(), mThingyListener, mDevice);
        updateUi();
        return rootView;
    }

    @Override
    public void onStart() {
        super.onStart();
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mIsFragmentAttached = true;
        if (context instanceof CloudFragmentListener) {
            mListener = (CloudFragmentListener) context;
        } else {
            throw new RuntimeException(context.toString()
                    + " must implement CloudFragmentListener");
        }
    }


    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong("UPLOADED", mUploadedSize);
        outState.putLong("DOWNLOADED", mDownloadedSize);
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mIsFragmentAttached = false;
        mListener = null;
    }

    @Override
    public void onStop() {
        super.onStop();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        ThingyListenerHelper.unregisterThingyListener(getContext(), mThingyListener);
    }

    @Override
    public void onTokenChanged() {
        final String token = Utils.getIFTTTToken(getContext());
        if (token.isEmpty()) {
            mTemperatureSwitch.setChecked(false);
            mPressureSwitch.setChecked(false);
            mButtonStateSwitch.setChecked(false);
            mCloudTokenView.setText(R.string.dash);
        } else {
            mCloudToken = token;
            if(mTemperatureSwitch.isChecked()) {
                mDatabaseHelper.enableCloudNotifications(mDevice.getAddress(), mTemperatureSwitch.isChecked(), CloudDbColumns.COLUMN_TEMPERATURE_UPLOAD);
                mThingySdkManager.enableTemperatureNotifications(mDevice, mTemperatureSwitch.isChecked());
            }

            if(mPressureSwitch.isChecked()) {
                mDatabaseHelper.enableCloudNotifications(mDevice.getAddress(), mPressureSwitch.isChecked(), CloudDbColumns.COLUMN_PRESSURE_UPLOAD);
                mThingySdkManager.enablePressureNotifications(mDevice, mPressureSwitch.isChecked());
            }

            if(mButtonStateSwitch.isChecked()) {
                mDatabaseHelper.enableCloudNotifications(mDevice.getAddress(), mButtonStateSwitch.isChecked(), CloudDbColumns.COLUMN_BUTTON_STATE_UPLOAD);
                mThingySdkManager.enableButtonStateNotification(mDevice, mButtonStateSwitch.isChecked());
            }
            mCloudTokenView.setText(token);
        }
    }

    private void updateUi() {
        final int temperatureInterval = mThingySdkManager.getEnvironmentTemperatureInterval(mDevice);
        final int pressureInterval = mThingySdkManager.getEnvironmentTemperatureInterval(mDevice);
        final int buttonState = mThingySdkManager.getEnvironmentTemperatureInterval(mDevice);
        mCloudToken = Utils.getIFTTTToken(getContext());

        mTemperatureIntervalView.setText((String.valueOf(temperatureInterval)));
        mPressureIntervalView.setText((String.valueOf(pressureInterval)));
        updateButtonState(buttonState);

        if (!mCloudToken.isEmpty()) {
            mCloudTokenView.setText(mCloudToken);
        }

        mUploadedView.setText(Utils.humanReadableByteCount(mUploadedSize, true));
        mDownloadedView.setText(Utils.humanReadableByteCount(mDownloadedSize, true));
    }

    private void updateButtonState(final int buttonState) {
        switch (buttonState) {
            case ThingyUtils.BUTTON_STATE_RELEASED:
                mButtonStateView.setText(R.string.button_state_released);
                break;
            case ThingyUtils.BUTTON_STATE_PRESSED:
                mButtonStateView.setText(R.string.button_state_pressed);
                break;
            default:
                mButtonStateView.setText(R.string.button_state_unknown);
                break;
        }
    }

    private void uploadData(final int eventType, final String jsonData) {
        CloudTask cloudTask = new CloudTask(eventType, jsonData);
        cloudTask.execute();
    }

    private String createTemperatureJson(final String value) {
        final JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("value1", mDatabaseHelper.getDeviceName(mDevice.getAddress()));
            jsonObject.put("value2", value);
            jsonObject.put("value3", "\u2103");
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return jsonObject.toString();

    }

    private String createPressureJson(final String value) {
        final JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("value1", mDatabaseHelper.getDeviceName(mDevice.getAddress()));
            jsonObject.put("value2", value);
            jsonObject.put("value3", "hP");
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return jsonObject.toString();

    }

    private String createButtonStateJson(final float duration) {
        final JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("value1", mDatabaseHelper.getDeviceName(mDevice.getAddress()));
            jsonObject.put("value2", TIME_FORMAT.format(duration));
            jsonObject.put("value3", "seconds");
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return jsonObject.toString();

    }

    private void writeStream(final OutputStream outputStream, final String jsonData) {
        try {
            outputStream.write(jsonData.getBytes());
            outputStream.flush();
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mUploadedSize += jsonData.getBytes().length;
                    mUploadedView.setText(Utils.humanReadableByteCount(mUploadedSize, true));
                }
            });
        } catch (IOException e) {

            e.printStackTrace();
        } finally {
            try {
                outputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void readStream(final InputStream inputStream) {
        BufferedReader br = null;
        try {
            StringBuffer sb = new StringBuffer();
            br = new BufferedReader(new InputStreamReader(inputStream));
            String inputLine;
            while ((inputLine = br.readLine()) != null) {
                sb.append(inputLine);
            }
            final String result = sb.toString();
            final JSONObject resultJson = new JSONObject(result);
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    handleErrors(resultJson);
                }
            });
        } catch (IOException e) {

            e.printStackTrace();
        } catch (JSONException e) {
            e.printStackTrace();
        } finally {
            try {
                if(br != null) {
                    br.close();
                }
                inputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void handleErrors(final JSONObject resultJson) {
        try {
            if(resultJson.has("errors")) {
                mTemperatureSwitch.setChecked(false);
                mPressureSwitch.setChecked(false);
                mButtonStateSwitch.setChecked(false);
                final JSONArray errorArr = resultJson.getJSONArray("errors");
                for(int i = 0; i < errorArr.length(); i++) {
                    final JSONObject messageJson = errorArr.getJSONObject(i);
                    if(messageJson.has("message")){
                        final String errorMessage = messageJson.getString("message");
                        MessageDialogFragment fragment = MessageDialogFragment.newInstance(getString(R.string.ifttt_error), errorMessage);
                        fragment.show(getChildFragmentManager(), null);
                        break;
                    }
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public class CloudTask extends AsyncTask<Void, Void, Void> {
        private static final String TEMPERATURE_BASE_URL = "https://maker.ifttt.com/trigger/temperature_update/with/key/";
        private static final String PRESSURE_BASE_URL = "https://maker.ifttt.com/trigger/pressure_update/with/key/";
        private static final String BUTTON_STATE_BASE_URL = "https://maker.ifttt.com/trigger/button_press/with/key/";
        private final String json;
        private final int eventType;

        public CloudTask(final int eventType, final String json) {
            this.eventType = eventType;
            this.json = json;
        }

        @Override
        protected Void doInBackground(Void... params) {
            HttpURLConnection urlConnection = null;
            URL url = null;
            try {
                switch (eventType) {
                    case TEMPERATURE_UPDATE_EVENT:
                        url = new URL(TEMPERATURE_BASE_URL + mCloudToken);
                        break;
                    case PRESSURE_UPDATE_EVENT:
                        url = new URL(PRESSURE_BASE_URL + mCloudToken);
                        break;
                    case BUTTON_STATE_UPDATE_EVENT:
                        url = new URL(BUTTON_STATE_BASE_URL + mCloudToken);
                        break;
                }

                urlConnection = (HttpURLConnection) url.openConnection();
                urlConnection.setDoOutput(true);
                urlConnection.setRequestProperty("Content-Type", "application/json");
                urlConnection.connect();

                OutputStream out = new BufferedOutputStream(urlConnection.getOutputStream());
                writeStream(out, json);

                InputStream stream =  new BufferedInputStream(urlConnection.getErrorStream());
                readStream(stream);
                final String message = urlConnection.getResponseMessage();
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mDownloadedSize += message.getBytes().length;
                        mDownloadedView.setText(Utils.humanReadableByteCount(mDownloadedSize, true));
                    }
                });

            } catch (MalformedURLException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if (urlConnection != null)
                    urlConnection.disconnect();
            }
            return null;
        }
    }
}