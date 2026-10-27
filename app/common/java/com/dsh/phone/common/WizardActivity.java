package com.dsh.phone.common;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import java.net.InetSocketAddress;
import java.net.Socket;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * One-tap deployment wizard base.
 *
 * UX contract: no title bar; the API key is stored in local SharedPreferences
 * after the first successful deploy and prefilled afterwards; when DSH is already
 * running on 127.0.0.1:3080 the shell opens immediately; after a successful deploy
 * the shell opens automatically — there is no manual "open" button.
 *
 * Third-party API: an optional endpoint field (OpenAI-compatible relay) is saved
 * alongside the key and passed to the deploy scripts as DSH_BASE_URL.
 */
public abstract class WizardActivity extends Activity {
    private static final String PREFS = "dsh_phone";
    private static final String KEY_API = "api_key";
    private static final String KEY_BASE = "api_base";
    private static final String KEY_DEPLOYED = "deployed";

    protected TextView statusView;
    protected TextView logView;
    protected EditText keyInput;
    protected EditText baseInput;
    protected Button deployBtn;
    private final StringBuilder logBuf = new StringBuilder();
    private boolean deploying = false;

    /** Short mode label, e.g. "Root 版" / "Shizuku 版". */
    protected abstract String modeLabel();

    /** Deploy on a background thread. Throwing reports failure and ends the run. */
    protected abstract void doDeploy(String apiKey, String baseUrl) throws Exception;

    /** WebView shell activity, opened automatically once DSH is reachable. */
    protected abstract Class<?> shellActivityClass();

    /** Bring a previously-deployed DSH back up (lightweight, no reinstall).
     *  Throwing reports failure; the wizard then offers a full redeploy. */
    protected abstract void resumeDsh() throws Exception;

    /** Stop any stale DSH web process. Called after a failed deploy/resume so a
     *  half-finished state cannot masquerade as "already deployed" (port 3080
     *  still answering from the old process) on the next launch. */
    protected void killStaleDsh() {}

    protected boolean isDeployed() {
        return "1".equals(prefs().getString(KEY_DEPLOYED, null));
    }

    protected SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    /** Blocking TCP probe of the on-device DSH port. */
    protected boolean dshReachable(int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 3080), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Open the WebView shell. */
    protected void openShell() {
        try {
            Intent i = new Intent(this, shellActivityClass());
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // No system action bar; the custom content fills the window.
        try { requestWindowFeature(Window.FEATURE_NO_TITLE); } catch (Exception ignored) {}

        LinearLayout root = Ui.vbox(this);

        statusView = Ui.text(this, "状态：等待开始", 14, false);
        statusView.setTextColor(Color.parseColor("#4460A5"));
        root.addView(statusView, lp(0, 4, 0, 10));

        TextView keyLabel = Ui.text(this, "API Key（官方 DeepSeek 或第三方中转，只需填一次）", 13, true);
        root.addView(keyLabel, lp());
        keyInput = Ui.edit(this, "粘贴你的 API Key", true);
        // Prefill the key stored after the first successful deploy.
        String saved = prefs().getString(KEY_API, null);
        if (saved != null && !saved.isEmpty()) {
            if (!saved.contains("\n")) {
                keyInput.setText(saved);
            } else {
                // Stale/garbage value (e.g. an accidental paste): drop it.
                prefs().edit().remove(KEY_API).apply();
            }
        }
        root.addView(keyInput, lp());
        TextView keyHint = Ui.text(this, "Key 只保存在本机 App 与 Termux 环境里，不内置、不上传、不进安装包。", 11, false);
        keyHint.setTextColor(Color.parseColor("#888888"));
        root.addView(keyHint, lp(0, 2, 0, 8));

        // Optional third-party endpoint (OpenAI-compatible relay).
        TextView baseLabel = Ui.text(this, "API 地址（可选：第三方中转填，如 https://…/v1；留空 = 官方）", 13, true);
        root.addView(baseLabel, lp(0, 10, 0, 4));
        baseInput = Ui.edit(this, "留空使用官方 api.deepseek.com", false);
        String savedBase = prefs().getString(KEY_BASE, null);
        if (savedBase != null && !savedBase.isEmpty()) {
            baseInput.setText(savedBase);
        }
        root.addView(baseInput, lp());
        TextView baseHint = Ui.text(this, "填了以后 DSH 走第三方线路：模型列表自动从该地址拉取；地址只存本机。", 11, false);
        baseHint.setTextColor(Color.parseColor("#888888"));
        root.addView(baseHint, lp(0, 2, 0, 8));

        deployBtn = Ui.button(this, "一键部署");
        root.addView(deployBtn, lp(0, 4, 0, 8));
        deployBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startDeploy();
            }
        });

        logView = Ui.text(this, "日志将显示在这里。", 11, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        ScrollView logScroll = Ui.logScroll(this, logView);
        logScroll.setBackgroundColor(Color.parseColor("#F5F7FA"));
        root.addView(logScroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        // DSH already running? Straight to the shell — never show the wizard
        // again once it has been deployed.
        if (dshReachable(1500)) {
            openShell();
            return;
        }
        // Deployed before but DSH is down (killed by MIUI, reboot without
        // boot-persistence, ...): restart it quietly instead of reinstalling.
        if (isDeployed()) {
            autoResume();
        }
    }

    private LinearLayout.LayoutParams lp() {
        return lp(0, 4, 0, 4);
    }

    private LinearLayout.LayoutParams lp(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(l, t, r, b);
        return p;
    }

    protected void setStatus(String s) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusView.setText("状态：" + s);
            }
        });
    }

    protected void log(String msg) {
        final String line = msg + "\n";
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                logBuf.append(line);
                if (logBuf.length() > 60000) logBuf.delete(0, logBuf.length() - 60000);
                logView.setText(logBuf.toString());
            }
        });
    }

    protected boolean isDeploying() {
        return deploying;
    }

    /** Quietly bring DSH back up after a successful earlier deploy. */
    private void autoResume() {
        if (deploying) return;
        deploying = true;
        deployBtn.setEnabled(false);
        log("检测到已部署，正在启动 DSH（无需重新安装）…");
        setStatus("正在启动 DSH…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                try {
                    resumeDsh();
                    ok = true;
                    setStatus("DSH 已启动，正在打开界面…");
                } catch (Exception e) {
                    log("[error] DSH 启动失败：" + (e.getMessage() == null ? e.toString() : e.getMessage()));
                    try { killStaleDsh(); } catch (Exception ignored) {}
                    log("[hint] 如果多次失败，点下面的“一键部署”可完整修复。");
                    setStatus("DSH 启动失败，可点部署修复");
                } finally {
                    deploying = false;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            deployBtn.setEnabled(true);
                        }
                    });
                }
                if (ok) openShell();
            }
        }, "dsh-resume").start();
    }

    private void startDeploy() {
        if (deploying) return;
        final String key = keyInput.getText().toString().trim();
        if (key.isEmpty()) {
            setStatus("请先粘贴 API Key");
            return;
        }
        // Official keys are sk-…; third-party relays issue other shapes
        // (pk-…, JWT, …) — only reject what would actually break the shell.
        if (key.length() > 200 || key.contains("\n") || key.contains(" ")) {
            setStatus("Key 格式不对：应为单行纯文本（≤200 字符），无空格无换行。请重新粘贴。");
            return;
        }
        final String baseUrl = baseInput.getText().toString().trim();
        if (!baseUrl.isEmpty() && !baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            setStatus("API 地址应以 http:// 或 https:// 开头，或留空使用官方。");
            return;
        }
        deploying = true;
        deployBtn.setEnabled(false);
        logBuf.setLength(0);
        logView.setText("");
        log("=== " + modeLabel() + " 一键部署开始 ===");
        setStatus("部署中…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                try {
                    doDeploy(key, baseUrl);
                    // Successful deploy: remember state + key + endpoint (device-only).
                    prefs().edit().putString(KEY_DEPLOYED, "1").apply();
                    prefs().edit().putString(KEY_API, key).apply();
                    prefs().edit().putString(KEY_BASE, baseUrl).apply();
                    ok = true;
                    setStatus("部署完成，正在打开界面…");
                } catch (Exception e) {
                    log("[error] " + (e.getMessage() == null ? e.toString() : e.getMessage()));
                    try { killStaleDsh(); } catch (Exception ignored) {}
                    setStatus("部署失败，请查看日志");
                } finally {
                    deploying = false;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            deployBtn.setEnabled(true);
                        }
                    });
                }
                if (ok) openShell();
            }
        }, "dsh-deploy").start();
    }
}
