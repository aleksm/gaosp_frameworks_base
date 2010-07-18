/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.app.ProgressDialog;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.IBluetooth;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Handler;
import android.os.Power;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.Vibrator;
import android.os.storage.IMountService;
import android.os.storage.IMountShutdownObserver;

import com.android.internal.telephony.ITelephony;
import android.util.Log;
import android.view.WindowManager;


public final class ShutdownThread extends Thread {
    // constants
    private static final String TAG = "ShutdownThread";
    private static final int MAX_NUM_PHONE_STATE_READS = 16;
    private static final int PHONE_STATE_POLL_SLEEP_MSEC = 500;
    // maximum time we wait for the shutdown broadcast before going on.
    private static final int MAX_BROADCAST_TIME = 10*1000;
    private static final int MAX_SHUTDOWN_WAIT_TIME = 20*1000;

    // length of vibration before shutting down
    private static final int SHUTDOWN_VIBRATE_MS = 500;
    
    // state tracking
    private static Object sIsStartedGuard = new Object();
    private static boolean sIsStarted = false;
    
    private static boolean mReboot;
    private static String mRebootReason;

    // static instance of this thread
    private static final ShutdownThread sInstance = new ShutdownThread();

    // drakaz : reboot recovery variable
    private static boolean sIsRecovery = false;
    
    private final Object mActionDoneSync = new Object();
    private boolean mActionDone;
    private Context mContext;
    private PowerManager mPowerManager;
    private PowerManager.WakeLock mWakeLock;
    private Handler mHandler;
    
    private ShutdownThread() {
    }
 
    /**
     * Request a clean shutdown, waiting for subsystems to clean up their
     * state etc.  Must be called from a Looper thread in which its UI
     * is shown.
     *
     * @param context Context used to display the shutdown progress dialog.
     * @param confirm true if user confirmation is needed before shutting down.
     */
    public static void shutdown(final Context context, boolean confirm) {
        // ensure that only one thread is trying to power down.
        // any additional calls are just returned
        // drakaz : reboot recovery
	int MessageReboot;    
        synchronized (sIsStartedGuard){
            if (sIsStarted) {
                Log.d(TAG, "Request to shutdown already running, returning.");
                return;
            }
        }

        Log.d(TAG, "Notifying thread to start radio shutdown");

	if (sIsRecovery) {
		MessageReboot = com.android.internal.R.string.reboot_recovery_confirm;
	} else if (mReboot) {
		MessageReboot = com.android.internal.R.string.reboot_confirm;
	} else {
		MessageReboot = (com.android.internal.R.string.shutdown_confirm);
	}	
        
        if (confirm) {
            final AlertDialog dialog;
            // Set different dialog message based on whether or not we're rebooting
            if (sIsRecovery) {
		dialog = new AlertDialog.Builder(context)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setTitle(com.android.internal.R.string.global_action_reboot_recovery)
                        .setMessage(MessageReboot)
                        .setPositiveButton(com.android.internal.R.string.yes, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                beginShutdownSequence(context);
                            }
                        })
                        .setNegativeButton(com.android.internal.R.string.no, null)
                        .create();
 	    } else if (mReboot) {
                dialog = new AlertDialog.Builder(context)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setTitle(com.android.internal.R.string.reboot_system)
                        .setMessage(MessageReboot)
                        .setPositiveButton(com.android.internal.R.string.yes, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                beginShutdownSequence(context);
                            }
                        })
                        .setNegativeButton(com.android.internal.R.string.no, null)
                        .create();
            } else {
                dialog = new AlertDialog.Builder(context)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setTitle(com.android.internal.R.string.power_off)
                        .setMessage(com.android.internal.R.string.shutdown_confirm)
                        .setPositiveButton(com.android.internal.R.string.yes, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                beginShutdownSequence(context);
                            }
				})
				.setNegativeButton(com.android.internal.R.string.no, null)
				.create();
		    }
		    dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
		    if (!context.getResources().getBoolean(
			    com.android.internal.R.bool.config_sf_slowBlur)) {
			dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
		    }
		    dialog.show();
		} else {
		    beginShutdownSequence(context);
        }
    }

    /**
     * Request a clean shutdown, waiting for subsystems to clean up their
     * state etc.  Must be called from a Looper thread in which its UI
     * is shown.
     *
     * @param context Context used to display the shutdown progress dialog.
     * @param reason code to pass to the kernel (e.g. "recovery"), or null.
     * @param confirm true if user confirmation is needed before shutting down.
     */
    public static void reboot(final Context context, String reason, boolean confirm) {
        mReboot = true;
        mRebootReason = reason;
        shutdown(context, confirm);
    }
    
    // drakaz : reboot recovery
    public static void RebootRecovery(final Context context, boolean confirm, final boolean reboot) {
       	sIsRecovery = true;
	mReboot = true;
        mRebootReason = "recovery";
       	shutdown(context, confirm);
    }


    private static void beginShutdownSequence(Context context) {
        synchronized (sIsStartedGuard) {
            sIsStarted = true;
        }

        // throw up an indeterminate system dialog to indicate radio is
        // shutting down.
        ProgressDialog pd = new ProgressDialog(context);
        if (mReboot) {
            pd.setTitle(context.getText(com.android.internal.R.string.reboot_system));
            // drakaz : reboot recovery
            if (sIsRecovery) {
		pd.setMessage(context.getText(com.android.internal.R.string.reboot_progress_recovery));
	    } else {
	    	pd.setMessage(context.getText(com.android.internal.R.string.reboot_progress));
	    }
        } else {
            pd.setTitle(context.getText(com.android.internal.R.string.power_off));
            pd.setMessage(context.getText(com.android.internal.R.string.shutdown_progress));
        }
        pd.setIndeterminate(true);
        pd.setCancelable(false);
        pd.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
        if (!context.getResources().getBoolean(
                com.android.internal.R.bool.config_sf_slowBlur)) {
            pd.getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        }

        pd.show();

        // start the thread that initiates shutdown
        sInstance.mContext = context;
        sInstance.mPowerManager = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        sInstance.mWakeLock = null;
        if (sInstance.mPowerManager.isScreenOn()) {
            try {
                sInstance.mWakeLock = sInstance.mPowerManager.newWakeLock(
                        PowerManager.FULL_WAKE_LOCK, "Shutdown");
                sInstance.mWakeLock.acquire();
            } catch (SecurityException e) {
                Log.w(TAG, "No permission to acquire wake lock", e);
                sInstance.mWakeLock = null;
            }
        }
        sInstance.mHandler = new Handler() {
        };
        sInstance.start();
    }

    void actionDone() {
        synchronized (mActionDoneSync) {
            mActionDone = true;
            mActionDoneSync.notifyAll();
        }
    }

    /**
     * Makes sure we handle the shutdown gracefully.
     * Shuts off power regardless of radio and bluetooth state if the alloted time has passed.
     */
    public void run() {
        boolean bluetoothOff;
        boolean radioOff;

        BroadcastReceiver br = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                // We don't allow apps to cancel this, so ignore the result.
                actionDone();
            }
        };
        
        Log.i(TAG, "Sending shutdown broadcast...");
        
        // First send the high-level shut down broadcast.
        mActionDone = false;
        mContext.sendOrderedBroadcast(new Intent(Intent.ACTION_SHUTDOWN), null,
                br, mHandler, 0, null, null);
        
        final long endTime = SystemClock.elapsedRealtime() + MAX_BROADCAST_TIME;
        synchronized (mActionDoneSync) {
            while (!mActionDone) {
                long delay = endTime - SystemClock.elapsedRealtime();
                if (delay <= 0) {
                    Log.w(TAG, "Shutdown broadcast timed out");
                    break;
                }
                try {
                    mActionDoneSync.wait(delay);
                } catch (InterruptedException e) {
                }
            }
        }
        
        Log.i(TAG, "Shutting down activity manager...");
        
        final IActivityManager am =
            ActivityManagerNative.asInterface(ServiceManager.checkService("activity"));
        if (am != null) {
            try {
                am.shutdown(MAX_BROADCAST_TIME);
            } catch (RemoteException e) {
            }
        }
        
        final ITelephony phone =
                ITelephony.Stub.asInterface(ServiceManager.checkService("phone"));
        final IBluetooth bluetooth =
                IBluetooth.Stub.asInterface(ServiceManager.checkService(
                        BluetoothAdapter.BLUETOOTH_SERVICE));

        final IMountService mount =
                IMountService.Stub.asInterface(
                        ServiceManager.checkService("mount"));
        
        try {
            bluetoothOff = bluetooth == null ||
                           bluetooth.getBluetoothState() == BluetoothAdapter.STATE_OFF;
            if (!bluetoothOff) {
                Log.w(TAG, "Disabling Bluetooth...");
                bluetooth.disable(false);  // disable but don't persist new state
            }
        } catch (RemoteException ex) {
            Log.e(TAG, "RemoteException during bluetooth shutdown", ex);
            bluetoothOff = true;
        }

        try {
            radioOff = phone == null || !phone.isRadioOn();
            if (!radioOff) {
                Log.w(TAG, "Turning off radio...");
                phone.setRadio(false);
            }
        } catch (RemoteException ex) {
            Log.e(TAG, "RemoteException during radio shutdown", ex);
            radioOff = true;
        }

        Log.i(TAG, "Waiting for Bluetooth and Radio...");
        
        // Wait a max of 32 seconds for clean shutdown
        for (int i = 0; i < MAX_NUM_PHONE_STATE_READS; i++) {
            if (!bluetoothOff) {
                try {
                    bluetoothOff =
                            bluetooth.getBluetoothState() == BluetoothAdapter.STATE_OFF;
                } catch (RemoteException ex) {
                    Log.e(TAG, "RemoteException during bluetooth shutdown", ex);
                    bluetoothOff = true;
                }
            }
            if (!radioOff) {
                try {
                    radioOff = !phone.isRadioOn();
                } catch (RemoteException ex) {
                    Log.e(TAG, "RemoteException during radio shutdown", ex);
                    radioOff = true;
                }
            }
            if (radioOff && bluetoothOff) {
                Log.i(TAG, "Radio and Bluetooth shutdown complete.");
                break;
            }
            SystemClock.sleep(PHONE_STATE_POLL_SLEEP_MSEC);
        }

        // Shutdown MountService to ensure media is in a safe state
        IMountShutdownObserver observer = new IMountShutdownObserver.Stub() {
            public void onShutDownComplete(int statusCode) throws RemoteException {
                Log.w(TAG, "Result code " + statusCode + " from MountService.shutdown");
                actionDone();
            }
        };

        Log.i(TAG, "Shutting down MountService");
        // Set initial variables and time out time.
        mActionDone = false;
        final long endShutTime = SystemClock.elapsedRealtime() + MAX_SHUTDOWN_WAIT_TIME;
        synchronized (mActionDoneSync) {
            try {
                if (mount != null) {
                    mount.shutdown(observer);
                } else {
                    Log.w(TAG, "MountService unavailable for shutdown");
                }
            } catch (Exception e) {
                Log.e(TAG, "Exception during MountService shutdown", e);
            }
            while (!mActionDone) {
                long delay = endShutTime - SystemClock.elapsedRealtime();
                if (delay <= 0) {
                    Log.w(TAG, "Shutdown wait timed out");
                    break;
                }
                try {
                    mActionDoneSync.wait(delay);
                } catch (InterruptedException e) {
                }
            }
        }

        if ((mReboot) || (sIsRecovery)) {
            Log.i(TAG, "Rebooting, reason: " + mRebootReason);
            try {
            	// drakaz : reboot recovery
		if (sIsRecovery) {
			Power.RebootRecovery(mRebootReason);
		} else {
			Power.reboot(mRebootReason);
		}
            } catch (Exception e) {
                Log.e(TAG, "Reboot failed, will attempt shutdown instead", e);
            }
        } else if (SHUTDOWN_VIBRATE_MS > 0) {
            // vibrate before shutting down
            Vibrator vibrator = new Vibrator();
            vibrator.vibrate(SHUTDOWN_VIBRATE_MS);
            // vibrator is asynchronous so we need to wait to avoid shutting down too soon.
            try {
                Thread.sleep(SHUTDOWN_VIBRATE_MS);
            } catch (InterruptedException e) {
            }
        }

	if (!sIsRecovery) {
        	// Shutdown power
        	Log.i(TAG, "Performing low-level shutdown...");
        	Power.shutdown();
	}
    }
}
