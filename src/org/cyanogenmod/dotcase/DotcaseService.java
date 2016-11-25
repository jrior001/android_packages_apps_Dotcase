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

import java.text.Normalizer;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;

public class DotcaseService extends Service {

    private static final String TAG = "Dotcase";

    private static final int COVER_STATE_CHANGED = 0;

    private Context mContext;
    private PowerManager.WakeLock mWakeLock;
    private final IntentFilter mFilter = new IntentFilter();
    private PowerManager mPowerManager;

    private final Object mLock = new Object();

    private int mSwitchState = 0;


    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Creating service");
        mContext = this;

        mFilter.addAction(Intent.ACTION_SCREEN_ON);
        mFilter.addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        mFilter.addAction("com.android.deskclock.ALARM_ALERT");
        mFilter.addAction(cyanogenmod.content.Intent.ACTION_COVER_CHANGE);
        // add other alarm apps here

        mContext.getApplicationContext().registerReceiver(receiver, mFilter);
        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);

    }


    private final Handler mHandler = new Handler(true) {
        @Override
        public void handleMessage(Message msg) {

            switch (msg.what) {
                case COVER_STATE_CHANGED:
                    handleCoverChange(msg.arg1);
                    mWakeLock.release();
                    break;
            }
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            if (intent.getAction().equals(cyanogenmod.content.Intent.ACTION_COVER_CHANGE))  {
                int mLidState = intent.getIntExtra(cyanogenmod.content.Intent.EXTRA_COVER_STATE, -1);
                if (mLidState != -1) { // ignore LID_ABSENT case
                    onCoverEvent(mLidState);
                }
            }
            Intent i = new Intent();
            if (intent.getAction().equals(TelephonyManager.ACTION_PHONE_STATE_CHANGED)) {
                String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
                if (state.equals("RINGING")) {

                    String number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
                    Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                            Uri.encode(number));
                    Cursor cursor = context.getContentResolver().query(uri,
                            new String[] {ContactsContract.PhoneLookup.DISPLAY_NAME},
                            number, null, null);
                    String name;
                    if (cursor.moveToFirst()) {
                        name = cursor.getString(cursor.getColumnIndex(
                                ContactsContract.PhoneLookup.DISPLAY_NAME));
                    } else {
                        name = "";
                    }
                    cursor.close();

                    if (number.equalsIgnoreCase("restricted")) {
                        // If call is restricted, don't show a number
                        name = number;
                        number = "";
                    }

                    name = normalize(name);
                    name = name + "  "; // Add spaces so the scroll effect looks good

                    Dotcase.sStatus.startRinging(number, name);
                    Dotcase.sStatus.setOnTop(true);
                    new Thread(new ensureTopActivity()).start();

                } else {
                    Dotcase.sStatus.setOnTop(false);
                    Dotcase.sStatus.stopRinging();
                }
            } else if (intent.getAction().equals("com.android.deskclock.ALARM_ALERT")) {
                // add other alarm apps here
                Dotcase.sStatus.startAlarm();
                Dotcase.sStatus.setOnTop(true);
                new Thread(new ensureTopActivity()).start();
            } else if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                Dotcase.sStatus.resetTimer();
                intent.setAction(DotcaseConstants.ACTION_REDRAW);
                mContext.sendBroadcast(intent);
                i.setClassName("org.cyanogenmod.dotcase", "org.cyanogenmod.dotcase.Dotcase");
                i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(i);
            }
        }
    };

    private void handleCoverChange(int state) {
        synchronized (mLock) {

            if(state == 0) {
                Log.e(TAG, "Cover Closed, Creating Dotcase Activity");
                Intent intent = new Intent(this, Dotcase.class);
                intent.setAction(DotcaseConstants.ACTION_COVER_CLOSED);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
            } else {
                Log.e(TAG, "Cover Opened, Killing Dotcase Activity");
                Intent intent = new Intent(DotcaseConstants.ACTION_KILL_ACTIVITY);
                mContext.sendBroadcastAsUser(intent, new UserHandle(UserHandle.USER_CURRENT));
            }
        }
    }

    private void onCoverEvent(int state) {

        Message message = new Message();
        message.what = COVER_STATE_CHANGED;
        message.arg1 = state;

        mWakeLock.acquire();
        mHandler.sendMessage(message);
    }

    /**
     * Normalizes a string to lowercase without diacritics
     */
    private static String normalize(String str) {
        return Normalizer.normalize(str.toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replaceAll("æ", "ae")
                .replaceAll("ð", "d")
                .replaceAll("ø", "o")
                .replaceAll("þ", "th")
                .replaceAll("ß", "ss")
                .replaceAll("œ", "oe");
    }

    private class ensureTopActivity implements Runnable {
        Intent i = new Intent();

        @Override
        public void run() {
            while ((Dotcase.sStatus.isRinging() || Dotcase.sStatus.isAlarm())
                    && Dotcase.sStatus.isOnTop()) {
                ActivityManager am =
                        (ActivityManager) mContext.getSystemService(Activity.ACTIVITY_SERVICE);
                if (!am.getRunningTasks(1).get(0).topActivity.getPackageName().equals(
                        "org.cyanogenmod.dotcase")) {
                    i.setClassName("org.cyanogenmod.dotcase", "org.cyanogenmod.dotcase.Dotcase");
                    i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    mContext.startActivity(i);
                }
                try {
                    Thread.sleep(100);
                } catch (IllegalArgumentException e) {
                    // This isn't going to happen
                } catch (InterruptedException e) {
                    Log.i(TAG, "Sleep interrupted", e);
                }
            }
        }
    }

    public IBinder onBind(Intent intent) {
        return null;
    }
}
