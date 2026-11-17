package com.sl.wifiguard;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** سطر جهاز موحّد يُستعمل بلوحة الراوتر وتبويب الأجهزة. الضغط يفتح شاشة الجهاز. */
final class DeviceRow {
    private DeviceRow() {
    }

    static View build(final Activity a, final RouterProfile profile, final boolean remote, final NetDevice d) {
        LinearLayout c = Ui.card(a);
        ((LinearLayout.LayoutParams) c.getLayoutParams()).bottomMargin = Ui.dp(a, 8);
        int p = Ui.dp(a, 12);
        c.setPadding(p, p, p, p);
        LinearLayout r = Ui.row(a);
        r.addView(Ui.text(a, d.icon(), 26, Ui.WHITE, false));
        LinearLayout mid = Ui.column(a);
        mid.setPadding(Ui.dp(a, 12), 0, Ui.dp(a, 8), 0);
        DeviceStore.Policy pol = DeviceStore.policy(a, profile.id, d.mac);
        String flags = (pol.blocked ? " 🚫" : "") + (pol.adultFilter ? " 🔞" : "") + (!pol.allowed.isEmpty() ? " ⏰" : "");
        TextView name = Ui.text(a, d.displayName() + flags + (d.isNew ? "  • جديد" : ""), 15,
                d.isNew ? Ui.GOLD : Ui.WHITE, true);
        name.setSingleLine(true);
        mid.addView(name);
        mid.addView(Ui.text(a, d.kindLabel() + (d.ip.isEmpty() ? "" : "  •  " + d.ip), 12, Ui.MUTED, false));
        r.addView(mid, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        TextView right = d.online
                ? Ui.text(a, "↓ " + NetDevice.speed(d.down), 12, d.down > 0 ? Ui.CYAN : Ui.MUTED, true)
                : Ui.text(a, DeviceStore.ago(d.lastSeen), 11, Ui.MUTED, false);
        r.addView(right);
        c.addView(r);
        if (!d.online) c.setAlpha(0.55f);
        c.setOnClickListener(v -> a.startActivity(new Intent(a, DeviceActivity.class)
                .putExtra(RouterActivity.EXTRA_ID, profile.id)
                .putExtra(RouterActivity.EXTRA_REMOTE, remote)
                .putExtra(DeviceActivity.EXTRA_MAC, d.mac)));
        return c;
    }
}
