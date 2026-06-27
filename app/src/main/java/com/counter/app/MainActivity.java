package com.counter.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.switchmaterial.SwitchMaterial;
import org.json.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends AppCompatActivity {

    // ── state ────────────────────────────────────────────────────────────
    private int count = 0;
    private String sessionName = "";
    private final List<ClickLog> clickLogs = new ArrayList<>();
    private final List<Session> sessions = new ArrayList<>();
    private boolean advancedMode = false;

    // ── views ────────────────────────────────────────────────────────────
    private TextView tvCount, tvSessionName, tvModeLabel;
    private SwitchMaterial switchMode;
    private ListView listSessions;
    private SessionAdapter adapter;

    private static final String PREF_SESSIONS = "counter_sessions";
    private static final String PREF_CURRENT  = "counter_current";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvCount       = findViewById(R.id.tvCount);
        tvSessionName = findViewById(R.id.tvSessionName);
        tvModeLabel   = findViewById(R.id.tvModeLabel);
        switchMode    = findViewById(R.id.switchMode);
        listSessions  = findViewById(R.id.listSessions);

        loadSessions();
        loadCurrent();   // ← 恢复上次未完成的当前任务

        adapter = new SessionAdapter();
        listSessions.setAdapter(adapter);
        listSessions.setOnItemClickListener((p, v, i, id) -> showSessionMenu(i));

        findViewById(R.id.btnCount).setOnClickListener(v -> handleCount());
        findViewById(R.id.btnReset).setOnClickListener(v -> handleReset());
        findViewById(R.id.tvClearAll).setOnClickListener(v -> confirmClearAll());

        switchMode.setOnCheckedChangeListener((b, checked) -> {
            advancedMode = checked;
            tvModeLabel.setText(checked ? "高级" : "基础");
        });

        updateDisplay();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveCurrent();   // ← App 切到后台或关闭时立即保存
    }

    // ── counting ─────────────────────────────────────────────────────────
    private void handleCount() {
        if (advancedMode) {
            showRemarkDialog(this::doCount);
        } else {
            doCount("");
        }
    }

    private void doCount(String remark) {
        count++;
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        clickLogs.add(new ClickLog(count, ts, remark));
        saveCurrent();   // ← 每次计数立即写盘
        updateDisplay();
        View btn = findViewById(R.id.btnCount);
        btn.setAlpha(0.6f);
        btn.animate().alpha(1f).setDuration(120).start();
    }

    private void updateDisplay() {
        tvCount.setText(String.valueOf(count));
        tvSessionName.setText(sessionName.isEmpty() ? "" : "项目：" + sessionName);
    }

    // ── remark dialog ─────────────────────────────────────────────────────
    private interface RemarkCallback { void onResult(String remark); }

    private void showRemarkDialog(RemarkCallback cb) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(8), dp(20), dp(4));

        TextView label = new TextView(this);
        label.setText("为本次点击添加备注（可选）");
        label.setTextColor(Color.parseColor("#90a4ae"));
        label.setTextSize(13);
        label.setPadding(0, 0, 0, dp(10));

        EditText et = new EditText(this);
        et.setHint("输入备注内容...");
        et.setMinLines(3);
        et.setBackgroundColor(Color.parseColor("#16213e"));
        et.setTextColor(Color.parseColor("#e8eaf6"));
        et.setHintTextColor(Color.parseColor("#546e7a"));
        et.setPadding(dp(10), dp(8), dp(10), dp(8));

        layout.addView(label);
        layout.addView(et);

        new AlertDialog.Builder(this)
            .setTitle("添加备注")
            .setView(layout)
            .setPositiveButton("确认", (d, w) -> cb.onResult(et.getText().toString().trim()))
            .setNegativeButton("跳过", (d, w) -> cb.onResult(""))
            .setOnCancelListener(d -> cb.onResult(""))
            .show();
    }

    // ── reset ─────────────────────────────────────────────────────────────
    private void handleReset() {
        if (count == 0) { doReset(); return; }

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(8), dp(20), dp(4));

        EditText et = new EditText(this);
        String defaultName = sessionName.isEmpty()
            ? "记录 " + new SimpleDateFormat("M月d日", Locale.CHINESE).format(new Date())
            : sessionName;
        et.setText(defaultName);
        et.setSelectAllOnFocus(true);
        et.setTextColor(Color.parseColor("#e8eaf6"));
        et.setHintTextColor(Color.parseColor("#546e7a"));
        layout.addView(et);

        new AlertDialog.Builder(this)
            .setTitle("保存并重置")
            .setMessage("请命名当前计数，保存后才能重置")
            .setView(layout)
            .setPositiveButton("确认重置", (d, w) -> {
                String name = et.getText().toString().trim();
                if (name.isEmpty()) name = defaultName;
                saveSession(name);
                doReset();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void saveSession(String name) {
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        sessions.add(0, new Session(name, count, new ArrayList<>(clickLogs), date));
        persistSessions();
        adapter.notifyDataSetChanged();
    }

    private void doReset() {
        count = 0; clickLogs.clear(); sessionName = "";
        saveCurrent();   // ← 重置后清空当前任务存档
        updateDisplay();
    }

    // ── session menu ──────────────────────────────────────────────────────
    private void showSessionMenu(int idx) {
        Session s = sessions.get(idx);
        String[] items = s.logs.isEmpty()
            ? new String[]{"▶  继续计数", "✕  删除记录"}
            : new String[]{"▶  继续计数", "⬇  导出为 CSV", "✕  删除记录"};

        new AlertDialog.Builder(this)
            .setTitle(s.name)
            .setItems(items, (d, which) -> {
                if (s.logs.isEmpty() && which == 1) which = 2;
                switch (which) {
                    case 0: resumeSession(idx); break;
                    case 1: exportSession(idx); break;
                    case 2: confirmDeleteSession(idx); break;
                }
            })
            .show();
    }

    private void resumeSession(int idx) {
        if (count > 0) {
            new AlertDialog.Builder(this)
                .setMessage("当前有未保存的计数，继续将丢失。确定继续？")
                .setPositiveButton("继续", (d, w) -> doResume(idx))
                .setNegativeButton("取消", null).show();
        } else {
            doResume(idx);
        }
    }

    private void doResume(int idx) {
        Session s = sessions.remove(idx);
        persistSessions();
        count = s.count; sessionName = s.name;
        clickLogs.clear(); clickLogs.addAll(s.logs);
        saveCurrent();   // ← 恢复后立即写盘
        updateDisplay();
        adapter.notifyDataSetChanged();
    }

    // ── export CSV ────────────────────────────────────────────────────────
    private void exportSession(int idx) {
        Session s = sessions.get(idx);
        if (s.logs.isEmpty()) {
            toast("该记录无详细日志（需高级模式下保存）");
            return;
        }
        try {
            File dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (dir == null) dir = getFilesDir();
            if (!dir.exists()) dir.mkdirs();

            String fname = s.name.replaceAll("[\\\\/:*?\"<>|]", "_") + "_计数记录.csv";
            File file = new File(dir, fname);
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), "UTF-8"));
            bw.write("\uFEFF"); // BOM，Excel 正确显示中文
            bw.write("序号,时间,备注\n");
            for (ClickLog log : s.logs) {
                String remark = log.remark.replace("\"", "\"\"");
                bw.write(log.seq + "," + log.time + ",\"" + remark + "\"\n");
            }
            bw.write("\n项目名称," + s.name + "\n");
            bw.write("总计数," + s.count + "\n");
            bw.close();
            toast("已导出：" + file.getAbsolutePath());
        } catch (IOException e) {
            toast("导出失败：" + e.getMessage());
        }
    }

    private void confirmDeleteSession(int idx) {
        new AlertDialog.Builder(this)
            .setMessage("确定删除「" + sessions.get(idx).name + "」？")
            .setPositiveButton("删除", (d, w) -> {
                sessions.remove(idx);
                persistSessions();
                adapter.notifyDataSetChanged();
            })
            .setNegativeButton("取消", null).show();
    }

    private void confirmClearAll() {
        if (sessions.isEmpty()) return;
        new AlertDialog.Builder(this)
            .setMessage("确定清除所有历史记录？此操作不可恢复。")
            .setPositiveButton("清除", (d, w) -> {
                sessions.clear();
                persistSessions();
                adapter.notifyDataSetChanged();
            })
            .setNegativeButton("取消", null).show();
    }

    // ── persistence ───────────────────────────────────────────────────────

    /** 加载历史记录列表 */
    private void loadSessions() {
        SharedPreferences prefs = getPreferences(Context.MODE_PRIVATE);
        String json = prefs.getString(PREF_SESSIONS, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                List<ClickLog> logs = new ArrayList<>();
                JSONArray la = o.optJSONArray("logs");
                if (la != null) {
                    for (int j = 0; j < la.length(); j++) {
                        JSONObject lo = la.getJSONObject(j);
                        logs.add(new ClickLog(lo.getInt("seq"), lo.getString("time"), lo.optString("remark", "")));
                    }
                }
                sessions.add(new Session(o.getString("name"), o.getInt("count"), logs, o.optString("date", "")));
            }
        } catch (JSONException e) { /* ignore */ }
    }

    /** 加载上次未完成的当前任务 */
    private void loadCurrent() {
        SharedPreferences prefs = getPreferences(Context.MODE_PRIVATE);
        String json = prefs.getString(PREF_CURRENT, "{}");
        try {
            JSONObject o = new JSONObject(json);
            count = o.optInt("count", 0);
            sessionName = o.optString("sessionName", "");
            JSONArray la = o.optJSONArray("logs");
            if (la != null) {
                for (int i = 0; i < la.length(); i++) {
                    JSONObject lo = la.getJSONObject(i);
                    clickLogs.add(new ClickLog(lo.getInt("seq"), lo.getString("time"), lo.optString("remark", "")));
                }
            }
        } catch (JSONException e) { /* ignore */ }
    }

    /** 保存当前进行中的任务（每次计数/恢复/重置/退出时调用） */
    private void saveCurrent() {
        try {
            JSONObject o = new JSONObject();
            o.put("count", count);
            o.put("sessionName", sessionName);
            JSONArray la = new JSONArray();
            for (ClickLog l : clickLogs) {
                JSONObject lo = new JSONObject();
                lo.put("seq", l.seq); lo.put("time", l.time); lo.put("remark", l.remark);
                la.put(lo);
            }
            o.put("logs", la);
            getPreferences(Context.MODE_PRIVATE).edit()
                .putString(PREF_CURRENT, o.toString()).apply();
        } catch (JSONException e) { /* ignore */ }
    }

    /** 保存历史记录列表 */
    private void persistSessions() {
        try {
            JSONArray arr = new JSONArray();
            for (Session s : sessions) {
                JSONObject o = new JSONObject();
                o.put("name", s.name); o.put("count", s.count); o.put("date", s.date);
                JSONArray la = new JSONArray();
                for (ClickLog l : s.logs) {
                    JSONObject lo = new JSONObject();
                    lo.put("seq", l.seq); lo.put("time", l.time); lo.put("remark", l.remark);
                    la.put(lo);
                }
                o.put("logs", la);
                arr.put(o);
            }
            getPreferences(Context.MODE_PRIVATE).edit()
                .putString(PREF_SESSIONS, arr.toString()).apply();
        } catch (JSONException e) { /* ignore */ }
    }

    // ── adapter ───────────────────────────────────────────────────────────
    private class SessionAdapter extends BaseAdapter {
        @Override public int getCount() { return sessions.size(); }
        @Override public Object getItem(int i) { return sessions.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View cv, ViewGroup parent) {
            if (cv == null) cv = LayoutInflater.from(MainActivity.this)
                .inflate(R.layout.item_session, parent, false);
            Session s = sessions.get(i);
            ((TextView) cv.findViewById(R.id.tvName)).setText(s.name);
            String meta = s.date + (s.logs.isEmpty() ? "" : " · " + s.logs.size() + " 条备注");
            ((TextView) cv.findViewById(R.id.tvMeta)).setText(meta);
            ((TextView) cv.findViewById(R.id.tvSessionCount)).setText(String.valueOf(s.count));
            cv.setBackgroundColor(Color.parseColor("#16213e"));
            cv.setPadding(dp(14), dp(14), dp(14), dp(14));
            return cv;
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); }

    // ── data classes ──────────────────────────────────────────────────────
    static class ClickLog {
        int seq; String time, remark;
        ClickLog(int seq, String time, String remark) { this.seq=seq; this.time=time; this.remark=remark; }
    }
    static class Session {
        String name, date; int count; List<ClickLog> logs;
        Session(String name, int count, List<ClickLog> logs, String date) {
            this.name=name; this.count=count; this.logs=logs; this.date=date;
        }
    }
}
