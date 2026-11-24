package com.escuela.impresion;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Enciende el servidor automáticamente cuando la tablet se reinicia. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            ServerService.start(context);
        }
    }
}
