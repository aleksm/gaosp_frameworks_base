/*
 * Copyright (C) 2007 Google Inc.
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

package com.android.internal.app;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.IMountService;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.widget.Toast;

/**
 * This activity is shown to the user for him/her to disable USB mass storage.
 * It uses the alert dialog style. It will be launched from a notification.
 */
public class UsbStorageStopActivity extends AlertActivity implements DialogInterface.OnClickListener {

    private static final int POSITIVE_BUTTON = AlertDialog.BUTTON_POSITIVE;

    /** Used to detect when the USB cable is unplugged, so we can call finish() */
    private BroadcastReceiver mBatteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction() == Intent.ACTION_BATTERY_CHANGED) {
                handleBatteryChanged(intent);
            }
        }
    };
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Set up the "dialog"
        final AlertController.AlertParams p = mAlertParams;
        p.mIconId = com.android.internal.R.drawable.ic_dialog_alert;
        p.mTitle = getString(com.android.internal.R.string.usb_storage_stop_title);
        p.mMessage = getString(com.android.internal.R.string.usb_storage_stop_message);
        p.mPositiveButtonText = getString(com.android.internal.R.string.usb_storage_stop_button_mount);
        p.mPositiveButtonListener = this;
        p.mNegativeButtonText = getString(com.android.internal.R.string.usb_storage_stop_button_unmount);
        p.mNegativeButtonListener = this;
        setupAlert();
    }

    @Override
    protected void onResume() {
        super.onResume();

        registerReceiver(mBatteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    @Override
    protected void onPause() {
        super.onPause();
        
        unregisterReceiver(mBatteryReceiver);
    }

    /**
     * {@inheritDoc}
     */
    public void onClick(DialogInterface dialog, int which) {

        if (which == POSITIVE_BUTTON) {
            stopUsbStorage();
        }

        // No matter what, finish the activity
        finish();
    }

    private void stopUsbStorage() {
        IMountService mountService = IMountService.Stub.asInterface(ServiceManager
                .getService("mount"));
        if (mountService == null) {
            showStoppingError();
            return;
        }

        try {
            mountService.setMassStorageEnabled(false);
        } catch (RemoteException e) {
            showStoppingError();
            return;
        }
    }

    private void handleBatteryChanged(Intent intent) {
        int pluggedType = intent.getIntExtra("plugged", 0);
        if (pluggedType == 0) {
            // It was disconnected from the plug, so finish
            finish();
        }
    }
    
    private void showStoppingError() {
        Toast.makeText(this, com.android.internal.R.string.usb_storage_stop_error_message,
                Toast.LENGTH_LONG).show();
    }

}
