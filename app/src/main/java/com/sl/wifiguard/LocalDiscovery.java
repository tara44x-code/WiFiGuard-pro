package com.sl.wifiguard;

import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** اكتشاف العناوين المتجاوبة ضمن نطاق /24 واحد (254 عنواناً كحد أقصى). */
final class LocalDiscovery {
    interface Listener {
        void complete(List<String> hosts);
    }

    private static final int[] PORTS = {80, 443, 53, 22, 445, 8080, 62078};

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    void scan(final String localIp, final Listener listener) {
        coordinator.execute(() -> {
            final List<String> hosts = Collections.synchronizedList(new ArrayList<String>());
            int dot = localIp.lastIndexOf('.');
            if (dot > 0) {
                final String subnet = localIp.substring(0, dot + 1);
                ExecutorService pool = Executors.newFixedThreadPool(48);
                for (int i = 1; i <= 254; i++) {
                    final String host = subnet + i;
                    pool.execute(() -> {
                        if (!cancelled.get() && (host.equals(localIp) || probe(host))) hosts.add(host);
                    });
                }
                pool.shutdown();
                try {
                    if (!pool.awaitTermination(40, TimeUnit.SECONDS)) pool.shutdownNow();
                } catch (InterruptedException e) {
                    pool.shutdownNow();
                }
            }
            final List<String> sorted = new ArrayList<>(hosts);
            Collections.sort(sorted, LocalDiscovery::compareIp);
            if (!cancelled.get()) ui.post(() -> listener.complete(sorted));
        });
    }

    void cancel() {
        cancelled.set(true);
        coordinator.shutdownNow();
    }

    private static int compareIp(String a, String b) {
        return Integer.compare(lastOctet(a), lastOctet(b));
    }

    private static int lastOctet(String ip) {
        try {
            return Integer.parseInt(ip.substring(ip.lastIndexOf('.') + 1));
        } catch (Exception e) {
            return 0;
        }
    }

    private static boolean probe(String host) {
        try {
            if (InetAddress.getByName(host).isReachable(350)) return true;
        } catch (IOException ignored) {
        }
        for (int port : PORTS) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), 180);
                return true;
            } catch (IOException e) {
                // رفض الاتصال يعني أن الجهاز موجود لكن المنفذ مغلق.
                String m = e.getMessage();
                if (m != null && m.contains("ECONNREFUSED")) return true;
            }
        }
        return false;
    }
}
