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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

public class BootCompletedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Settings.Secure.putString(context.getContentResolver(),
                                  Settings.Secure.IMMERSIVE_MODE_CONFIRMATIONS,
                                  "org.cyanogenmod.dotcase");

        context.startService(new Intent(context, DotcaseService.class));
    }
}
