package io.dsh.adbbridge;

import android.app.Activity;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 极简 UI：建立/重建 ADB 桥、只自检、故障兜底重注入。下方是完整日志。
 * 刻意只用最老的 Android API（编译期 android.jar 为 API 16 stub），规避 stub 缺失。
 */
public class MainActivity extends Activity {

    private TextView log;
    private Button setup;
    private Button check;
    private Button reinject;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("ADB 桥\n把宿主 adb 公钥写入 adb_keys，并让 adbd 监听 TCP 5555");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        root.addView(title);

        setup = new Button(this);
        setup.setText("① 建立 / 重建 ADB 桥（需要 ROOT）");
        setup.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runAsync(Mode.SETUP);
            }
        });
        root.addView(setup);

        check = new Button(this);
        check.setText("② 只自检（不改任何东西）");
        check.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runAsync(Mode.CHECK);
            }
        });
        root.addView(check);

        reinject = new Button(this);
        reinject.setText("③ 兜底：重新注入 key 并重开端口");
        reinject.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runAsync(Mode.REINJECT);
            }
        });
        root.addView(reinject);

        log = new TextView(this);
        log.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        log.setGravity(Gravity.START);
        log.setTextIsSelectable(true);
        log.setMovementMethod(new ScrollingMovementMethod());
        log.setText("正在自检…（首次会弹出 ROOT 授权框，请点“允许”）\n");

        ScrollView scroll = new ScrollView(this);
        scroll.addView(log);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        // 启动即静默自检一次，便于直接看到当前状态
        runAsync(Mode.CHECK);
    }

    private enum Mode {SETUP, CHECK, REINJECT}

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

    private void runAsync(final Mode mode) {
        setup.setEnabled(false);
        check.setEnabled(false);
        reinject.setEnabled(false);
        log.setText("");
        append(mode == Mode.CHECK ? "自检中…\n\n" : "执行中（授权框请点“允许”）…\n\n");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BridgeCore.Result r;
                    if (mode == Mode.SETUP) {
                        r = BridgeCore.runAll();
                    } else if (mode == Mode.REINJECT) {
                        r = BridgeCore.reInject();
                    } else {
                        r = BridgeCore.health();
                    }
                    append(r.text());
                } catch (Throwable t) {
                    append("异常: " + t + "\n");
                } finally {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setup.setEnabled(true);
                            check.setEnabled(true);
                            reinject.setEnabled(true);
                        }
                    });
                }
            }
        }).start();
    }
}
