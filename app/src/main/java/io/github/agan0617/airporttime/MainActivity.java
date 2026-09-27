package io.github.agan0617.airporttime;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 查機場捷運兩站之間、今天某個時間之後的班次。
 * 發車時刻：TDX 捷運車站時刻表（StationTimeTable/TYMC，依起站抓，快取 6 小時）；
 * 抵達時間：起站發車＋打包在 assets/tymc.json 的站間行駛時間（依車種：1 普通車、2 直達車）。
 */
public class MainActivity extends Activity {
    private static final String API = "https://tdx.transportdata.tw/api/basic/v2/Rail/Metro/StationTimeTable/TYMC?%24format=JSON&%24filter=";
    private static final String DEFAULT_FROM = "A1";  // 台北車站
    private static final String DEFAULT_TO = "A12";   // 機場第一航廈

    private final List<String[]> stations = new ArrayList<>();          // {id, name}，照路線順序
    private final Map<String, Integer> order = new HashMap<>();
    private final Map<String, Map<String, Integer>> travel = new HashMap<>(); // 車種 → "A1>A12" → 秒
    private final Set<String> expressStops = new HashSet<>();
    private final List<Train> trains = new ArrayList<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private String fromId = DEFAULT_FROM, toId = DEFAULT_TO;
    private LocalTime time;
    private boolean timeSetByUser;
    private int querySeq;

    private TextView fromView, toView, timeView, statusView;
    private TrainAdapter adapter;
    private SharedPreferences cache;

    static class Train {
        String dep, arr, dest;
        int type, minutes;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        cache = getSharedPreferences("timetable_cache", MODE_PRIVATE);
        loadStatic();

        fromView = findViewById(R.id.from);
        toView = findViewById(R.id.to);
        timeView = findViewById(R.id.time);
        statusView = findViewById(R.id.status);
        ListView list = findViewById(R.id.list);
        adapter = new TrainAdapter();
        list.setAdapter(adapter);

        fromView.setOnClickListener(v -> pickStation(true));
        toView.setOnClickListener(v -> pickStation(false));
        findViewById(R.id.swap).setOnClickListener(v -> {
            String t = fromId; fromId = toId; toId = t;
            query();
        });
        timeView.setOnClickListener(v -> new TimePickerDialog(this, (tp, h, m) -> {
            time = LocalTime.of(h, m);
            timeSetByUser = true;
            query();
        }, time.getHour(), time.getMinute(), true).show());
        timeView.setOnLongClickListener(v -> {   // 長按回到「現在」
            timeSetByUser = false;
            time = nowMinute();
            query();
            return true;
        });
        findViewById(R.id.query).setOnClickListener(v -> query());
        time = nowMinute();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!timeSetByUser) time = nowMinute();
        query();
    }

    private static LocalTime nowMinute() {
        return LocalTime.now().withSecond(0).withNano(0);
    }

    private void loadStatic() {
        try (InputStream in = getAssets().open("tymc.json")) {
            JSONObject o = new JSONObject(readAll(in));
            JSONArray st = o.getJSONArray("stations");
            for (int i = 0; i < st.length(); i++) {
                JSONArray s = st.getJSONArray(i);
                stations.add(new String[]{s.getString(0), s.getString(1)});
                order.put(s.getString(0), i);
            }
            JSONObject tr = o.getJSONObject("travel");
            for (java.util.Iterator<String> it = tr.keys(); it.hasNext(); ) {
                String type = it.next();
                JSONObject m = tr.getJSONObject(type);
                Map<String, Integer> mm = new HashMap<>();
                for (java.util.Iterator<String> k = m.keys(); k.hasNext(); ) {
                    String key = k.next();
                    mm.put(key, m.getInt(key));
                    if (type.equals("2")) for (String sid : key.split(">")) expressStops.add(sid);
                }
                travel.put(type, mm);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 顯示用站名：「三重站」→「三重」，台北車站照舊 */
    private String nameOf(String id) {
        for (String[] s : stations) if (s[0].equals(id)) return s[1].endsWith("站") && !s[1].equals("台北車站") ? s[1].substring(0, s[1].length() - 1) : s[1];
        return id;
    }

    private void pickStation(boolean isFrom) {
        String[] names = new String[stations.size()];
        for (int i = 0; i < names.length; i++) {
            String id = stations.get(i)[0];
            names[i] = id + "  " + nameOf(id) + (expressStops.contains(id) ? "　（直達車停靠）" : "");
        }
        new AlertDialog.Builder(this)
                .setTitle(isFrom ? "選擇起站" : "選擇終站")
                .setItems(names, (d, i) -> {
                    if (isFrom) fromId = stations.get(i)[0]; else toId = stations.get(i)[0];
                    query();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void query() {
        fromView.setText(nameOf(fromId));
        toView.setText(nameOf(toId));
        timeView.setText(String.format("%02d:%02d", time.getHour(), time.getMinute()));
        if (fromId.equals(toId)) {
            show(new ArrayList<>(), "起站和終站一樣");
            return;
        }
        final int seq = ++querySeq;
        final String from = fromId, to = toId;
        final LocalTime after = time;
        final LocalDate today = LocalDate.now();
        final String key = from + "|" + today;
        statusView.setText("查詢中…");
        io.execute(() -> {
            String err = null;
            List<Train> result = new ArrayList<>();
            try {
                String json = cache.getString(key, null);
                long age = System.currentTimeMillis() - cache.getLong(key + "@t", 0);
                if (json == null || age > 6 * 60 * 60 * 1000L) {
                    try {
                        String f = URLEncoder.encode("StationID eq '" + from + "'", "UTF-8").replace("+", "%20");
                        String fresh = fetch(API + f);
                        SharedPreferences.Editor ed = cache.edit();
                        for (String k : cache.getAll().keySet()) if (!k.contains(today.toString())) ed.remove(k);
                        ed.putString(key, fresh).putLong(key + "@t", System.currentTimeMillis()).apply();
                        json = fresh;
                    } catch (Exception e) {
                        if (json == null) throw e;
                    }
                }
                result = parse(json, from, to, after, today);
            } catch (QuotaException e) {
                err = "TDX 免金鑰的每日查詢次數用完了，明天再試";
            } catch (Exception e) {
                err = "查詢失敗：" + e.getMessage();
            }
            final String error = err;
            final List<Train> r = result;
            main.post(() -> {
                if (seq != querySeq) return;
                if (error != null) { show(new ArrayList<>(), error); return; }
                boolean holiday = isHoliday(today);
                String head = today.getMonthValue() + "/" + today.getDayOfMonth() + (holiday ? "（假日）" : "（平日）") + " "
                        + timeView.getText() + " 以後，" + nameOf(from) + " → " + nameOf(to);
                show(r, r.isEmpty() ? head + "：今天沒有班次了" : head + "，共 " + r.size() + " 班");
            });
        });
    }

    /** 週六日算假日；國定假日 TDX 時刻表也標在「假日」，但手機不知道哪天是國定假日，只看星期 */
    private static boolean isHoliday(LocalDate d) {
        return d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    private void show(List<Train> list, String status) {
        trains.clear();
        trains.addAll(list);
        adapter.notifyDataSetChanged();
        statusView.setText(status);
    }

    static class QuotaException extends Exception {}

    private static String fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "application/json");
        // TDX 免金鑰只放行瀏覽器（同 TraTime）
        c.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36");
        c.setRequestProperty("Accept-Encoding", "identity");
        int code = c.getResponseCode();
        if (code == 429) throw new QuotaException();
        if (code != 200) throw new Exception("HTTP " + code);
        try (InputStream in = c.getInputStream()) {
            return readAll(in);
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private static int toMin(String hhmm) {
        return Integer.parseInt(hhmm.substring(0, 2)) * 60 + Integer.parseInt(hhmm.substring(3, 5));
    }

    private static String fmt(int min) {
        min = ((min % 1440) + 1440) % 1440;
        return String.format("%02d:%02d", min / 60, min % 60);
    }

    List<Train> parse(String json, String from, String to, LocalTime after, LocalDate day) throws Exception {
        JSONArray arr = new JSONArray(json);
        int fromIdx = order.get(from), toIdx = order.get(to);
        int dir = toIdx > fromIdx ? 0 : 1;
        boolean holiday = isHoliday(day);
        int afterMin = after.getHour() * 60 + after.getMinute();
        List<Train> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.getJSONObject(i);
            if (e.optInt("Direction") != dir) continue;
            JSONObject sd = e.optJSONObject("ServiceDay");
            if (sd != null && sd.optBoolean(holiday ? "Saturday" : "Monday") == false) continue;
            String dest = e.optString("DestinationStaionID", e.optString("DestinationStationID"));
            Integer destIdx = order.get(dest);
            if (destIdx == null) continue;
            // 這班要開得到終站（往機場方向終點要在終站之後，回台北方向要在終站之前）
            if (dir == 0 ? destIdx < toIdx : destIdx > toIdx) continue;
            JSONArray tt = e.getJSONArray("Timetables");
            for (int k = 0; k < tt.length(); k++) {
                JSONObject t = tt.getJSONObject(k);
                int type = t.optInt("TrainType", 1);
                Map<String, Integer> tr = travel.get(String.valueOf(type));
                Integer sec = tr == null ? null : tr.get(from + ">" + to);
                if (sec == null) continue;   // 直達車不停這兩站之一
                String dep = t.getString("DepartureTime");
                // 過午夜的末班車（00:xx）算今天的尾巴
                int depMin = toMin(dep);
                if (depMin < 4 * 60) depMin += 1440;
                if (depMin < afterMin) continue;
                Train x = new Train();
                x.type = type;
                x.dep = dep;
                x.minutes = Math.round(sec / 60f);
                x.arr = fmt(depMin + x.minutes);
                x.dest = nameOf(dest);
                out.add(x);
            }
        }
        out.sort((a, b) -> {
            int am = toMin(a.dep), bm = toMin(b.dep);
            if (am < 240) am += 1440;
            if (bm < 240) bm += 1440;
            return Integer.compare(am, bm);
        });
        return out;
    }

    class TrainAdapter extends BaseAdapter {
        @Override public int getCount() { return trains.size(); }
        @Override public Object getItem(int i) { return trains.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View v, ViewGroup parent) {
            if (v == null) v = LayoutInflater.from(MainActivity.this).inflate(R.layout.row_train, parent, false);
            Train t = trains.get(i);
            ((TextView) v.findViewById(R.id.times)).setText(t.dep + "  →  " + t.arr);
            ((TextView) v.findViewById(R.id.info)).setText(t.minutes + " 分　往" + t.dest);
            TextView type = v.findViewById(R.id.type);
            type.setText(t.type == 2 ? "直達車" : "普通車");
            type.setTextColor(getColor(t.type == 2 ? R.color.type_express : R.color.type_local));
            ((TextView) v.findViewById(R.id.trainNo)).setText("");
            return v;
        }
    }
}
