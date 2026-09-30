package com.christorng.codextrigger;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity {
    private TextView status;
    private EditText ntfy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        refreshUi();
    }

    private void buildUi() {
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Codex Quota Trigger");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        status = new TextView(this);
        status.setTextIsSelectable(true);
        status.setPadding(0, pad, 0, pad);
        root.addView(status);

        ntfy = new EditText(this);
        ntfy.setHint("ntfy topic URL, e.g. https://ntfy.sh/your-topic");
        ntfy.setSingleLine(true);
        ntfy.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        ntfy.setText(Scheduler.prefs(this).getString(Scheduler.KEY_NTFY, ""));
        root.addView(ntfy);

        root.addView(button("Save ntfy URL", v -> {
            Scheduler.prefs(this).edit().putString(Scheduler.KEY_NTFY, ntfy.getText().toString().trim()).apply();
            refreshUi();
        }));

        root.addView(button("Sign in with ChatGPT", v -> startLogin()));

        root.addView(button("Check now", v -> runAsync(false)));

        root.addView(button("Trigger test (uses quota)", v -> runAsync(true)));

        root.addView(button("Enable scheduler", v -> {
            Scheduler.setEnabled(this, true);
            if (!Scheduler.canExact(this)) {
                try { startActivity(Scheduler.exactAlarmSettingsIntent(this)); } catch (Exception ignored) {}
            }
            runAsync(false);
        }));

        root.addView(button("Disable scheduler", v -> {
            Scheduler.setEnabled(this, false);
            refreshUi();
        }));

        root.addView(button("Exact alarm settings", v -> {
            try { startActivity(Scheduler.exactAlarmSettingsIntent(this)); } catch (Exception ignored) {}
        }));

        root.addView(button("Sign out", v -> {
            new CodexClient(this).signOut();
            Scheduler.setEnabled(this, false);
            refreshUi();
        }));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private Button button(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(listener);
        return b;
    }

    private void refreshUi() {
        CodexClient c = new CodexClient(this);
        String account = c.signedIn() ? "signed in" : "not signed in";
        status.setText(
                "Account: " + account +
                "\nScheduler: " + (Scheduler.enabled(this) ? "enabled" : "disabled") +
                "\nExact alarm permission: " + (Scheduler.canExact(this) ? "granted" : "not granted") +
                "\nNext alarm: " + Scheduler.formattedNext(this) +
                "\n\nLast status:\n" + Scheduler.lastStatus(this)
        );
    }

    private void runAsync(boolean forceTrigger) {
        status.setText("Running...");
        new Thread(() -> {
            Scheduler.runCycle(getApplicationContext(), forceTrigger);
            runOnUiThread(this::refreshUi);
        }, "codex-manual").start();
    }

    private void startLogin() {
        status.setText("Starting OAuth...");
        new Thread(() -> {
            try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                int port = server.getLocalPort();
                String redirect = "http://127.0.0.1:" + port + "/auth/callback";
                CodexClient.Pkce pkce = CodexClient.newPkce();
                String url = CodexClient.authorizeUrl(pkce, redirect);

                runOnUiThread(() -> {
                    Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(browser);
                    status.setText("Waiting for browser callback...");
                });

                try (Socket socket = server.accept()) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    String first = br.readLine();
                    if (first == null || !first.startsWith("GET ")) throw new IllegalStateException("Invalid OAuth callback");
                    String path = first.split(" ")[1];

                    while (true) {
                        String line = br.readLine();
                        if (line == null || line.isEmpty()) break;
                    }

                    URI uri = new URI("http://127.0.0.1" + path);
                    Map<String,String> query = parseQuery(uri.getRawQuery());
                    String code = query.get("code");
                    String state = query.get("state");
                    String error = query.get("error");

                    String html;
                    if (error != null) {
                        html = "<html><body><h2>Codex login failed</h2><p>" + escape(error) + "</p></body></html>";
                    } else {
                        html = "<html><body><h2>Codex login received</h2><p>You can return to the app.</p></body></html>";
                    }
                    byte[] body = html.getBytes(StandardCharsets.UTF_8);
                    String headers = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " +
                            body.length + "\r\nConnection: close\r\n\r\n";
                    OutputStream os = socket.getOutputStream();
                    os.write(headers.getBytes(StandardCharsets.US_ASCII));
                    os.write(body);
                    os.flush();

                    if (error != null) throw new IllegalStateException("OAuth error: " + error);
                    if (code == null || !pkce.state.equals(state)) throw new IllegalStateException("OAuth state/code mismatch");
                    new CodexClient(this).exchangeCode(code, pkce.verifier, redirect);
                }

                Scheduler.prefs(this).edit().putString(Scheduler.KEY_LAST, "Login successful").apply();
                runOnUiThread(this::refreshUi);
            } catch (Exception e) {
                Scheduler.prefs(this).edit().putString(Scheduler.KEY_LAST, "Login ERROR: " + e.getMessage()).apply();
                runOnUiThread(this::refreshUi);
            }
        }, "codex-oauth").start();
    }

    private static Map<String,String> parseQuery(String raw) throws Exception {
        Map<String,String> map = new HashMap<>();
        if (raw == null) return map;
        for (String pair : raw.split("&")) {
            String[] kv = pair.split("=", 2);
            String k = URLDecoder.decode(kv[0], "UTF-8");
            String v = kv.length > 1 ? URLDecoder.decode(kv[1], "UTF-8") : "";
            map.put(k, v);
        }
        return map;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
