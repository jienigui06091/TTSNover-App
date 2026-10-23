package com.ttsnover.reader;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LoginActivity extends Activity {

    private EditText etServer, etUser, etPass;
    private Button btnLogin, btnRegister;
    private TextView tvMsg;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Prefs.loggedIn()) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_login);
        etServer = findViewById(R.id.etServer);
        etUser = findViewById(R.id.etUser);
        etPass = findViewById(R.id.etPass);
        btnLogin = findViewById(R.id.btnLogin);
        btnRegister = findViewById(R.id.btnRegister);
        tvMsg = findViewById(R.id.tvMsg);

        etServer.setText(Prefs.base());
        etUser.setText(Prefs.user());

        btnLogin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                submit(false);
            }
        });
        btnRegister.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                submit(true);
            }
        });
    }

    private void submit(final boolean register) {
        final String base = Api.normBase(etServer.getText().toString());
        final String u = etUser.getText().toString().trim();
        String p = etPass.getText().toString();
        if (base.isEmpty()) {
            tvMsg.setText("请填服务器地址，例如 https://books.example.com");
            return;
        }
        if (u.isEmpty() || p.length() < 6) {
            tvMsg.setText("用户名不能为空，密码至少 6 位");
            return;
        }
        Prefs.setBase(base);
        etServer.setText(base);
        busy(true, register ? "注册中…" : "登录中…");
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject body = new JSONObject();
                    body.put("username", u);
                    body.put("password", etPass.getText().toString());
                    body.put("device", "Android " + android.os.Build.MODEL);
                    JSONObject r = Api.post(register ? "/api/auth/register" : "/api/auth/login", body);
                    final String token = r.optString("token", "");
                    if (token.isEmpty()) throw new Exception("服务端没有返回 token");
                    Prefs.setToken(token);
                    JSONObject user = r.optJSONObject("user");
                    Prefs.setUser(user != null ? user.optString("username", u) : u);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            busy(false, "");
                            startActivity(new Intent(LoginActivity.this, MainActivity.class));
                            finish();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            busy(false, "");
                            tvMsg.setText(Api.errText(e));
                        }
                    });
                }
            }
        });
    }

    private void busy(boolean on, String msg) {
        btnLogin.setEnabled(!on);
        btnRegister.setEnabled(!on);
        btnLogin.setText(on ? "请稍候…" : "登录");
        if (!TextUtils.isEmpty(msg)) tvMsg.setText(msg);
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }
}
