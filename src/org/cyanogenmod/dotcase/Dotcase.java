/*
 * Copyright (c) 2014 The CyanogenMod Project
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 * Also add information on how to contact you by electronic and paper mail.
 *
 */

package org.cyanogenmod.dotcase;

import org.cyanogenmod.dotcase.DotcaseConstants.Notification;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import android.telecom.TelecomManager;

import java.lang.Math;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

public class Dotcase extends Activity implements SensorEventListener
{
    private static final String TAG = "Dotcase";

    private final IntentFilter mFilter = new IntentFilter();
    private GestureDetector mDetector;
    private PowerManager mPowerManager;
    private SensorManager mSensorManager;
    private static Context mContext;

    static DotcaseStatus sStatus = new DotcaseStatus();

    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        mContext = this;

        mFilter.addAction(DotcaseConstants.ACTION_COVER_CLOSED);
        mFilter.addAction(DotcaseConstants.ACTION_KILL_ACTIVITY);
        mContext.getApplicationContext().registerReceiver(receiver, mFilter);

        getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON|
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED|
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL;
        getWindow().setAttributes(lp);

        final DrawView drawView = new DrawView(mContext);
        setContentView(drawView);

        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mSensorManager = (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);
        mDetector = new GestureDetector(mContext, new DotcaseGestureListener());
        sStatus.stopRunning();
    }

    @Override
    public void onStart() {
        super.onStart();

        new Thread(new Service()).start();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_PROXIMITY) {
            if (event.values[0] < event.sensor.getMaximumRange() && !sStatus.isPocketed()) {
                sStatus.setPocketed(true);
            } else if (sStatus.isPocketed()) {
                sStatus.setPocketed(false);
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    @Override
    protected void onResume() {
        super.onResume();
        mSensorManager.registerListener(this,
                mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY),
                SensorManager.SENSOR_DELAY_NORMAL);
        boolean screenOn = mPowerManager.isInteractive();
        // Starting up or comign back from screen off
        // Ensure device is awake and redraw
        if (!screenOn) {
            mPowerManager.wakeUp(SystemClock.uptimeMillis(), "Cover Closed");
        }
        Log.d(TAG, "Cover closed, Time to do work");
        Intent newIntent = new Intent();
        newIntent.setAction(DotcaseConstants.ACTION_REDRAW);
        mContext.sendBroadcast(newIntent);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mPowerManager.goToSleep(SystemClock.uptimeMillis());

        try {
            mSensorManager.unregisterListener(this);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Failed to unregister listener", e);
        }
    }

    class Service implements Runnable {
        @Override
        public void run() {
            if (!sStatus.isRunning()) {
                sStatus.startRunning();
                while (sStatus.isRunning()) {
                    Intent batteryIntent = mContext.getApplicationContext().registerReceiver(null,
                                             new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                    int timeout;

                    if(batteryIntent.getIntExtra("plugged", -1) > 0) {
                        timeout = 40;
                    } else {
                        timeout = 20;
                    }

                    for (int i = 0; i <= timeout; i++) {
                        if (sStatus.isResetTimer() || sStatus.isRinging() || sStatus.isAlarm()) {
                            i = 0;
                        }

                        if (!sStatus.isRunning()) {
                            return;
                        }

                        try {
                            Thread.sleep(500);
                        } catch (IllegalArgumentException e) {
                            // This isn't going to happen
                        } catch (InterruptedException e) {
                            Log.i(TAG, "Sleep interrupted", e);
                        }

                        Intent intent = new Intent();
                        intent.setAction(DotcaseConstants.ACTION_REDRAW);
                        mContext.sendBroadcast(intent);
                    }
                    mPowerManager.goToSleep(SystemClock.uptimeMillis());
                }
            }
        }
    }

    @Override
    public void onDestroy() {
        sStatus.stopRunning();
        super.onDestroy();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event){
        if (!sStatus.isPocketed()) {
            this.mDetector.onTouchEvent(event);
            return super.onTouchEvent(event);
        } else {
            // Say that we handled this event so nobody else does
            return true;
        }
    }

    class DotcaseGestureListener extends GestureDetector.SimpleOnGestureListener
    {

        @Override
        public boolean onDoubleTap(MotionEvent event) {
            //if screen on, screen off & "pause"
            boolean screenOn = mPowerManager.isInteractive();
            Log.d(TAG, "DT detected, screenon:" + screenOn );
            if(screenOn) {
                // Screen is on, trun it off and go to Sleep now
                onPause();
            }
            else {
                // screen was off, Resume
                onResume();
            }
            return true;
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            if (Math.abs(distanceY) > 60) {
                if (sStatus.isRinging()) {
                    sStatus.setOnTop(false);
                    TelecomManager telecomManager = (TelecomManager) mContext.getSystemService(Context.TELECOM_SERVICE);
                    if (distanceY < 60) {
                        telecomManager.endCall();
                    } else if (distanceY > 60) {
                        telecomManager.acceptRingingCall();
                    }
                } else if (sStatus.isAlarm()) {
                    Intent i = new Intent();
                    if (distanceY < 60) {
                        i.setAction("com.android.deskclock.ALARM_DISMISS");
                        sStatus.setOnTop(false);
                        mContext.sendBroadcast(i);
                        sStatus.stopAlarm();
                    } else if (distanceY > 60) {
                        i.setAction("com.android.deskclock.ALARM_SNOOZE");
                        sStatus.setOnTop(false);
                        mContext.sendBroadcast(i);
                        sStatus.stopAlarm();
                    }
                }
            }
            return true;
        }

        @Override
        public boolean onSingleTapUp (MotionEvent e) {
            sStatus.resetTimer();
            return true;
        }
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(DotcaseConstants.ACTION_KILL_ACTIVITY)) {
                try {
                    context.getApplicationContext().unregisterReceiver(receiver);
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "Failed to unregister receiver", e);
                }
                sStatus.stopRunning();
                finish();
                overridePendingTransition(0, 0);
                onDestroy();
            } else if (intent.getAction().equals(DotcaseConstants.ACTION_COVER_CLOSED)) {
                onResume();
            }
        }
    };

}
