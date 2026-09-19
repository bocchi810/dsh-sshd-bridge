package io.dsh.sshd;

import android.app.Activity;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 极简 UI：启动 SSH 控制通道、只自检、停止。下方是完整日志。
 * 刻意只用最老的 Android API（编译期 android.jar 为 API 16 stub）。
 */
public class MainActivity extends Activity {

    private TextView log;
    private Button start;
    private Button check;
    private Button stop;
    private Button diag;
    private volatile boolean serverRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("SSH 控制通道\n内置 dropbear，以 root 监听 " + SshdCore.PORT + " 端口，仅公钥认证");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        root.addView(title);

        start = new Button(this);
        start.setText("① 启动 SSH 服务（需要 ROOT）");
        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doStart();
            }
        });
        root.addView(start);

        check = new Button(this);
        check.setText("② 只自检（不改任何东西）");
        check.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doCheck();
            }
        });
        root.addView(check);

        stop = new Button(this);
        stop.setText("③ 停止 SSH 服务");
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doStop();
            }
        });
        root.addView(stop);

        diag = new Button(this);
        diag.setText("④ 导出诊断到共享存储（我直接读）");
        diag.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doDiag();
            }
        });
        root.addView(diag);

        log = new TextView(this);
        log.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        log.setGravity(Gravity.START);
        log.setTextIsSelectable(true);
        log.setMovementMethod(new ScrollingMovementMethod());
        log.setText("就绪。点 ① 后请在弹出的 ROOT 授权框点“允许”。\n\n");

        ScrollView scroll = new ScrollView(this);
        scroll.addView(log);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void append(final String s) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                log.append(s);
            }
        });
    }

    private void setBusy(final boolean busy) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                start.setEnabled(!busy);
                check.setEnabled(!busy);
                stop.setEnabled(!busy);
                diag.setEnabled(!busy);
            }
        });
    }

    /**
     * 把 assets 里的二进制与公钥解到**root 也能读**的暂存目录。
     *
     * <p>关键：不能用 getFilesDir()（/data/data/&lt;pkg&gt;/files，0700 且 SELinux 标签 app_data_file）
     * ——以 root 运行的 su 处于 ksu 域，跨域读它会被 SELinux 拒绝，表现为 cp 拷不过去。
     * 因此优先用外部私有目录 /storage/emulated/0/Android/data/&lt;pkg&gt;/files（root 可读），
     * 只有它不可用时才回落内部目录，并在日志里点明风险。
     */
    private String extractAssets() throws Exception {
        File dir = null;
        try {
            File ext = getExternalFilesDir(null);
            if (ext != null) {
                dir = new File(ext, "bin");
            }
        } catch (Throwable ignored) {
        }
        boolean external = dir != null;
        if (!external) {
            dir = new File(getFilesDir(), "bin");
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw new Exception("无法创建暂存目录 " + dir);
        }
        append("[staging] " + dir
                + (external ? "  (外部存储，root 可读)" : "  (内部存储，root 可能读不到!)") + "\n");

        AssetManager am = getAssets();
        String[] names = am.list("");
        if (names == null) names = new String[0];
        int copied = 0;
        for (String name : names) {
            if (!name.startsWith("dsh-")) continue;
            String target = name.substring("dsh-".length());
            File outFile = new File(dir, target);
            InputStream in = am.open(name);
            OutputStream out = new FileOutputStream(outFile);
            long total = 0;
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                }
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
            copied++;
            append("[staging] " + name + " -> " + target + "  " + total + " 字节"
                    + (outFile.canRead() ? "  可读" : "  **不可读**") + "\n");
        }
        if (copied == 0) {
            throw new Exception("assets 里没有 dsh-* 文件");
        }
        chmodViaShell(dir.getAbsolutePath());
        return dir.getAbsolutePath();
    }

    /** 放开暂存目录权限到 755/644，便于 root 跨域读取。 */
    private void chmodViaShell(String dir) {
        ShellRunner.Result r = ShellRunner.runFirstOk(
                "chmod 755 " + dir + " 2>&1; chmod 644 " + dir + "/* 2>&1; ls -la " + dir + " 2>&1",
                "sh -c");
        append("[staging chmod] " + r.all() + "\n");
    }

    private void doCheck() {
        setBusy(true);
        log.setText("");
        append("自检中…\n\n");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    append(SshdCore.status().text());
                } catch (Throwable t) {
                    append("异常: " + t + "\n");
                } finally {
                    setBusy(false);
                }
            }
        }).start();
    }

    private void doStop() {
        setBusy(true);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ShellRunner.Result r = ShellRunner.runFirstOk(
                            "pkill -f dsh-sshd/dropbear 2>/dev/null; sleep 1; "
                                    + "ps -A -o USER,PID,NAME 2>/dev/null | grep -w dropbear || echo '(已停止)'",
                            "su -c", "su 0 sh -c");
                    append("== 停止 ==\n" + r.all() + "\n");
                } catch (Throwable t) {
                    append("异常: " + t + "\n");
                } finally {
                    setBusy(false);
                }
            }
        }).start();
    }

    /** 诊断文件的落点：App 外部私有目录，容器可直接读。 */
    private String diagPath() {
        File ext = null;
        try {
            ext = getExternalFilesDir(null);
        } catch (Throwable ignored) {
        }
        if (ext == null) ext = getFilesDir();
        return new File(ext, "dsh-sshd-state.txt").getAbsolutePath();
    }

    private void doDiag() {
        setBusy(true);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String p = diagPath();
                    append("导出诊断到 " + p + " …\n");
                    ShellRunner.Result r = SshdCore.exportDiag(p);
                    append(r.all() + "\n");
                    append("\n--- 权限探针 ---\n");
                    append(SshdCore.probePerms().all() + "\n");
                } catch (Throwable t) {
                    append("异常: " + t + "\n");
                } finally {
                    setBusy(false);
                }
            }
        }).start();
    }

    private void doStart() {
        if (serverRunning) {
            append("服务已在运行中（本进程内）。若已断开，请点 ③ 停止后再启动。\n");
            return;
        }
        setBusy(true);
        log.setText("");
        append("启动中（授权框请点“允许”）…\n\n");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String tmp = extractAssets();
                    SshdCore.Result r = new SshdCore.Result();

                    ShellRunner.Result id = ShellRunner.runFirstOk("id", "su -c", "su 0 sh -c");
                    r.isRoot = id.out.contains("uid=0");
                    r.add("0. 提权自检（期望 uid=0）", id.all());
                    if (!r.isRoot) {
                        r.add("中止", "未取得 root，请在 root 管理器里授权本应用后重试。");
                        append(r.text());
                        return;
                    }

                    if (!SshdCore.install(tmp, diagPath(), r)) {
                        r.add("中止", "安装步骤失败，请看上面输出。");
                        append(r.text());
                        return;
                    }

                    r.deviceIp = SshdCore.detectIpv4();
                    append(r.text());
                    append("\n正在启动 dropbear（前台运行，日志会持续输出）…\n\n");

                    serverRunning = true;
                    ShellRunner.Result run = SshdCore.startServer();
                    append("== dropbear 退出 ==\nexit=" + run.exit + "\n" + run.all() + "\n");
                } catch (Throwable t) {
                    append("异常: " + t + "\n");
                } finally {
                    serverRunning = false;
                    setBusy(false);
                }
            }
        }).start();
    }
}
