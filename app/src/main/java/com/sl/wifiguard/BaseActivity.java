package com.sl.wifiguard;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** كل شاشة محمية ترث من هذا الكلاس: إذا كانت الجلسة مقفلة تُفتح شاشة الرمز السري أولاً. */
abstract class BaseActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.secureWindow(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Vault.appStarted(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Vault.isLocked()) startActivity(new Intent(this, LockActivity.class));
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations()) Vault.appStopped();
    }
}
