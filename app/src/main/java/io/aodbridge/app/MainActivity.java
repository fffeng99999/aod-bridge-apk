package io.aodbridge.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import io.aodbridge.common.Bridge;

/**
 * 配置界面：MiuiX（HyperOS）风格，纯系统控件实现（零第三方依赖，守住离线构建流水线）。
 *
 * 视觉规范（对照 miuix 设计语言）：
 *   - 大标题 30sp 粗体 + 13sp 灰色副标题；
 *   - 24dp 圆角卡片承载分组内容，卡片间距 12dp，左右留白 16dp；
 *   - 分组标题 14sp 灰字（MiSans 不可用，改用系统 sans-serif-medium）；
 *   - HyperOS 蓝 #3482FF：主按钮、开关、勾选框；
 *   - 主按钮为胶囊形填充，次按钮为浅灰胶囊；
 *   - 浅色 / 深色两套取色，跟随系统深色模式。
 */
public class MainActivity extends Activity {
    private EditText urlInput;
    private EditText tokenInput;
    private TextView statusView;
    private Switch shiftSwitch;
    private Switch keepAodSwitch;
    private Switch hideFpSwitch;
    private Spinner durationSpinner;
    private Spinner pollSpinner;
    private Spinner windowFromSpinner;
    private Spinner windowToSpinner;
    private CheckBox[] lineChecks;
    private Palette palette;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean accountTableLoading = false;

    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    /** 下拉项顺序：0=居中（默认）1=时钟下方 2=顶部 3=底部。 */
    /** 锁屏后显示时长档位（秒），0=保持显示；下拉顺序与之一一对应。 */
    private static final int[] DURATION_VALUES = {30, 60, 300, 600, 1800, Bridge.DURATION_KEEP};
    private static final String[] DURATION_LABELS = {
            "30 秒", "1 分钟", "5 分钟", "10 分钟", "30 分钟", "保持显示（不自动关闭）"
    };
    /** 轮询间隔档位（秒），下拉顺序与之一一对应。 */
    private static final int[] POLL_VALUES = {15, 30, 60, 120, 300, 600};
    private static final String[] POLL_LABELS = {
            "15 秒", "30 秒", "1 分钟", "2 分钟", "5 分钟", "10 分钟"
    };

    /** 显示时段起点档位（分钟）。 */
    private static final int[] WINDOW_FROM_VALUES = {0, 360, 420, 480, 540, 600, 660};
    private static final String[] WINDOW_FROM_LABELS = {
            "0:00", "6:00", "7:00", "8:00", "9:00", "10:00", "11:00"
    };
    /** 显示时段终点档位（分钟）。 */
    private static final int[] WINDOW_TO_VALUES = {
            720, 780, 840, 900, 960, 1020, 1080, 1140, 1200, 1260, 1320, 1380, 1440
    };
    private static final String[] WINDOW_TO_LABELS = {
            "12:00", "13:00", "14:00", "15:00", "16:00", "17:00",
            "18:00", "19:00", "20:00", "21:00", "22:00", "23:00", "全天"
    };

    /** 数据行勾选：位掩码与勾选框一一对应，默认全选。 */
    private static final int[] LINE_BITS = {
            Bridge.LINE_TODAY, Bridge.LINE_WEEK, Bridge.LINE_MONTH,
            Bridge.LINE_REQUESTS, Bridge.LINE_IO, Bridge.LINE_CACHE
    };
    private static final String[] LINE_LABELS = {
            "今日合计", "本周合计", "本月合计", "请求与错误", "输入与输出", "缓存命中"
    };

    /** MiuiX / HyperOS 取色（浅色与深色两套）。 */
    private static final class Palette {
        final int background;
        final int card;
        final int text;
        final int textSecondary;
        final int accent;
        final int field;
        final int secondaryButton;
        final int trackOff;

        private Palette(int background, int card, int text, int textSecondary, int accent,
                        int field, int secondaryButton, int trackOff) {
            this.background = background;
            this.card = card;
            this.text = text;
            this.textSecondary = textSecondary;
            this.accent = accent;
            this.field = field;
            this.secondaryButton = secondaryButton;
            this.trackOff = trackOff;
        }

        static Palette of(boolean dark) {
            if (dark) {
                return new Palette(0xFF191919, 0xFF242424, 0xFFF2F2F2, 0xFF8C8C8C,
                        0xFF3482FF, 0xFF303030, 0xFF303030, 0xFF4D4D4D);
            }
            return new Palette(0xFFF7F7F7, 0xFFFFFFFF, 0xFF1A1A1A, 0xFF999999,
                    0xFF3482FF, 0xFFF2F2F2, 0xFFF2F2F2, 0xFFD9D9D9);
        }
    }

    private final Runnable statusTicker = new Runnable() {
        @Override
        public void run() {
            statusView.setText(AppConfig.status(MainActivity.this));
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        boolean dark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        palette = Palette.of(dark);
        getWindow().setStatusBarColor(palette.background);
        getWindow().setNavigationBarColor(palette.background);
        if (!dark) {
            int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(palette.background);
        root.setPadding(0, dp(8), 0, dp(24));

        TextView title = text(Bridge.TITLE, 30, palette.text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(26), dp(8), dp(26), dp(2));
        root.addView(title);

        root.addView(sectionHeader("连接设置"));
        LinearLayout connectCard = newCard();
        urlInput = new EditText(this);
        urlInput.setHint("http://192.168.1.100:8790");
        urlInput.setText(AppConfig.url(this));
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        connectCard.addView(fieldBlock("中转地址", urlInput));
        tokenInput = new EditText(this);
        tokenInput.setHint("AOD_RELAY_TOKEN");
        tokenInput.setText(AppConfig.token(this));
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        connectCard.addView(fieldBlock("中转令牌", tokenInput));
        pollSpinner = spinner(POLL_LABELS, pollIndex(AppConfig.pollSec(this)));
        connectCard.addView(titleRow("轮询间隔", "每多久取一次数，改完点下方保存立即生效",
                pollSpinner));
        root.addView(connectCard);

        Button saveStart = pillButton("保存并启动轮询", true, v -> saveAndStart());
        root.addView(saveStart);
        root.addView(pillButton("停止轮询", false, v -> {
            AppConfig.setEnabled(this, false);
            stopPolling();
            AppConfig.setStatus(this, "已停止");
            statusView.setText(AppConfig.status(this));
        }));
        root.addView(pillButton("立即测试取数", false, v -> runOnceTest()));

        root.addView(sectionHeader("运行状态"));
        LinearLayout statusCard = newCard();
        statusView = text(AppConfig.status(this), 13, palette.textSecondary);
        statusView.setLineSpacing(0f, 1.25f);
        statusView.setPadding(dp(18), dp(14), dp(18), dp(14));
        statusView.setTextIsSelectable(true);
        statusCard.addView(statusView);
        root.addView(statusCard);

        root.addView(sectionHeader("息屏显示"));
        LinearLayout displayCard = newCard();
        displayCard.addView(titleRow("显示位置", "自动避开原生息屏内容（时钟/日期/电量/通知）与指纹区",
                null));
        durationSpinner = spinner(DURATION_LABELS, durationIndex(AppConfig.durationSec(this)));
        displayCard.addView(titleRow("锁屏后显示时长", "只影响本模块文字，与系统息屏时钟无关",
                durationSpinner));
        windowFromSpinner = spinner(WINDOW_FROM_LABELS,
                windowIndex(WINDOW_FROM_VALUES, AppConfig.windowFrom(this)));
        windowToSpinner = spinner(WINDOW_TO_LABELS,
                windowIndex(WINDOW_TO_VALUES, AppConfig.windowTo(this)));
        LinearLayout windowRow = newRow();
        LinearLayout windowLabels = new LinearLayout(this);
        windowLabels.setOrientation(LinearLayout.VERTICAL);
        windowLabels.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView windowName = text("显示时间段", 16, palette.text);
        windowName.setTypeface(MEDIUM);
        windowLabels.addView(windowName);
        TextView windowSub = text("一天中允许显示本模块文字的时段", 12, palette.textSecondary);
        windowSub.setPadding(0, dp(2), 0, 0);
        windowLabels.addView(windowSub);
        windowRow.addView(windowLabels);
        LinearLayout windowPickers = new LinearLayout(this);
        windowPickers.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams fromParams = new LinearLayout.LayoutParams(
                dp(96), ViewGroup.LayoutParams.WRAP_CONTENT);
        windowPickers.addView(windowFromSpinner, fromParams);
        TextView windowDash = text(" – ", 15, palette.textSecondary);
        windowDash.setGravity(Gravity.CENTER);
        windowPickers.addView(windowDash);
        LinearLayout.LayoutParams toParams = new LinearLayout.LayoutParams(
                dp(96), ViewGroup.LayoutParams.WRAP_CONTENT);
        windowPickers.addView(windowToSpinner, toParams);
        windowRow.addView(windowPickers);
        displayCard.addView(windowRow);
        shiftSwitch = miuixSwitch(AppConfig.shift(this));
        displayCard.addView(titleRow("防烧屏", "每分钟轻微移动显示位置", shiftSwitch));
        keepAodSwitch = miuixSwitch(AppConfig.keepAod(this));
        displayCard.addView(titleRow("息屏保活", "系统息屏到点也不熄灭，按上面的时长关闭", keepAodSwitch));
        hideFpSwitch = miuixSwitch(AppConfig.hideFp(this));
        displayCard.addView(titleRow("息屏指纹图标", "隐藏常亮的屏下指纹图标以防烧屏，不影响解锁", hideFpSwitch));
        root.addView(displayCard);

        root.addView(sectionHeader("AOD 显示哪些数据行"));
        LinearLayout linesCard = newCard();
        lineChecks = new CheckBox[LINE_BITS.length];
        int savedMask = AppConfig.linesMask(this);
        for (int i = 0; i < LINE_BITS.length; i++) {
            CheckBox box = miuixCheck((savedMask & LINE_BITS[i]) != 0);
            box.setContentDescription(LINE_LABELS[i]);
            lineChecks[i] = box;
            linesCard.addView(titleRow(LINE_LABELS[i], null, box));
        }
        root.addView(linesCard);

        root.addView(pillButton("只应用显示设置（不用重启轮询）", false, v -> applyDisplayOnly()));

        root.addView(sectionHeader("维护"));
        LinearLayout maintainCard = newCard();
        maintainCard.addView(actionRow("重启系统界面", "让设置 / hook 立即生效",
                "重启", v -> confirmRestartSystemUi()));
        root.addView(maintainCard);

        root.addView(sectionHeader("账号禁用"));
        LinearLayout accountCard = newCard();
        accountCard.setTag("account_card");
        accountCard.addView(text("加载中…", 13, palette.textSecondary));
        root.addView(accountCard);
        refreshAccountTable();

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(palette.background);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        handler.postDelayed(statusTicker, 2000);
    }

    // ---------------------------------------------------------------- MiuiX 组件

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private TextView text(String value, float sizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        return view;
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        return shape;
    }

    private GradientDrawable roundedSized(int color, float radiusDp, int widthDp, int heightDp) {
        GradientDrawable shape = rounded(color, radiusDp);
        shape.setSize(dp(widthDp), dp(heightDp));
        return shape;
    }

    private TextView sectionHeader(String title) {
        TextView header = text(title, 14, palette.textSecondary);
        header.setTypeface(MEDIUM);
        header.setPadding(dp(26), dp(18), dp(26), dp(6));
        return header;
    }

    private LinearLayout newCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(palette.card, 24));
        card.setClipToOutline(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(16), 0, dp(16), 0);
        card.setLayoutParams(params);
        return card;
    }

    private LinearLayout newRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(18), dp(10), dp(18), dp(10));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** 左侧标题（+可选灰字说明），右侧挂控件 —— MiuiX 列表行。 */
    private LinearLayout titleRow(String title, String summary, View trailing) {
        LinearLayout row = newRow();
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView name = text(title, 16, palette.text);
        name.setTypeface(MEDIUM);
        labels.addView(name);
        if (summary != null && !summary.isEmpty()) {
            TextView sub = text(summary, 12, palette.textSecondary);
            sub.setPadding(0, dp(2), 0, 0);
            labels.addView(sub);
        }
        row.addView(labels);
        if (trailing != null) {
            row.addView(trailing);
        }
        return row;
    }

    /** 输入区：标题在上、圆角输入框在下，模拟 HyperOS 文字输入卡片。 */
    private LinearLayout fieldBlock(String title, EditText input) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(18), dp(10), dp(18), dp(12));
        TextView name = text(title, 16, palette.text);
        name.setTypeface(MEDIUM);
        block.addView(name);
        input.setBackground(rounded(palette.field, 16));
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        input.setTextColor(palette.text);
        input.setHintTextColor(palette.textSecondary);
        input.setTextSize(15);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        block.addView(input, params);
        return block;
    }

    /** 可点击行（右侧蓝色动作文字），带系统水波纹反馈。 */
    private LinearLayout actionRow(String title, String summary, String action,
                                   View.OnClickListener listener) {
        LinearLayout row = titleRow(title, summary, text(action, 15, palette.accent));
        row.setClickable(true);
        TypedValue out = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, out, true)
                && out.resourceId != 0) {
            row.setBackgroundResource(out.resourceId);
        }
        row.setOnClickListener(listener);
        return row;
    }

    private Button pillButton(String label, boolean primary, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setTypeface(MEDIUM);
        button.setTextColor(primary ? 0xFFFFFFFF : palette.text);
        button.setBackground(rounded(primary ? palette.accent : palette.secondaryButton, 24));
        button.setPadding(dp(18), dp(13), dp(18), dp(13));
        button.setStateListAnimator(null);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(16), dp(8), dp(16), 0);
        button.setLayoutParams(params);
        button.setOnClickListener(listener);
        return button;
    }

    /** HyperOS 胶囊开关：关=浅灰 / 开=HyperOS 蓝，白色圆形滑块。 */
    private Switch miuixSwitch(boolean checked) {
        Switch toggle = new Switch(this);
        toggle.setShowText(false);
        toggle.setChecked(checked);
        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(0xFFFFFFFF);
        thumb.setSize(dp(24), dp(24));
        toggle.setThumbDrawable(thumb);
        StateListDrawable track = new StateListDrawable();
        track.addState(new int[] {android.R.attr.state_checked},
                roundedSized(palette.accent, 14, 46, 28));
        track.addState(new int[] {}, roundedSized(palette.trackOff, 14, 46, 28));
        toggle.setTrackDrawable(track);
        toggle.setTrackTintList(null);
        toggle.setThumbTintList(null);
        return toggle;
    }

    private CheckBox miuixCheck(boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setChecked(checked);
        box.setTextColor(palette.text);
        box.setButtonTintList(new ColorStateList(
                new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                new int[] {palette.accent, palette.textSecondary}));
        return box;
    }

    private Spinner spinner(String[] items, int selection) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(palette.text);
                view.setTextSize(15);
                return view;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                view.setTextColor(palette.text);
                view.setTextSize(15);
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(selection);
        return spinner;
    }

    // ---------------------------------------------------------------- 业务逻辑

    private void saveAndStart() {
        String url = urlInput.getText().toString().trim();
        String token = tokenInput.getText().toString().trim();
        AppConfig.save(this, url, token, AppConfig.range(this), true);
        AppConfig.setPollSec(this, selectedPollSec());
        saveDisplayFromUi();
        sendConfigBroadcast();
        requestNotificationPermissionIfNeeded();
        Intent intent = new Intent(this, PollService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        AppConfig.setStatus(this, "轮询已启动 → " + url);
        statusView.setText(AppConfig.status(this));
    }

    private void stopPolling() {
        stopService(new Intent(this, PollService.class));
    }

    private int selectedPollSec() {
        int index = pollSpinner == null ? -1 : pollSpinner.getSelectedItemPosition();
        if (index < 0 || index >= POLL_VALUES.length) {
            return Bridge.POLL_DEFAULT_SEC;
        }
        return POLL_VALUES[index];
    }

    private static int pollIndex(int seconds) {
        for (int i = 0; i < POLL_VALUES.length; i++) {
            if (POLL_VALUES[i] == seconds) {
                return i;
            }
        }
        return 2;
    }

    private static int windowIndex(int[] values, int minutes) {
        int best = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < values.length; i++) {
            int distance = Math.abs(values[i] - minutes);
            if (distance < bestDistance) {
                best = i;
                bestDistance = distance;
            }
        }
        return best;
    }

    private void runOnceTest() {
        final String url = urlInput.getText().toString().trim();
        final String token = tokenInput.getText().toString().trim();
        AppConfig.save(this, url, token, AppConfig.range(this), AppConfig.enabled(this));
        saveDisplayFromUi();
        statusView.setText("测试中…");
        new Thread(() -> {
            String result;
            try {
                JSONObject payload = RelayClient.fetchUsage(url, token, AppConfig.range(this));
                String text = UsageText.build(payload, AppConfig.linesMask(this));
                if (text.isEmpty()) {
                    text = payload.optString("text");
                }
                result = "成功：" + text
                        + "（source=" + payload.optString("source", "?")
                        + " cached=" + payload.optBoolean("cached", false) + "）";
            } catch (Exception error) {
                result = "失败：" + error.getMessage();
            }
            final String message = result;
            handler.post(() -> {
                AppConfig.setStatus(this, message);
                statusView.setText(AppConfig.status(this));
            });
        }, "aodbridge-test").start();
    }

    private void applyDisplayOnly() {
        saveDisplayFromUi();
        sendConfigBroadcast();
        AppConfig.setStatus(this, "显示设置已推送（AOD 下一轮刷新生效）");
        statusView.setText(AppConfig.status(this));
    }

    /** 参考 OShin 的重启逻辑：su -c "pkill -f 包名"，SystemUI 被结束后由系统自动拉起。 */
    private void confirmRestartSystemUi() {
        new AlertDialog.Builder(this)
                .setTitle("重启系统界面")
                .setMessage("将申请 root 结束 " + Bridge.TARGET_PACKAGE + " 进程，"
                        + "系统会在几秒内自动把它拉起来，使最新设置立即生效。\n"
                        + "屏幕可能短暂闪动；本应用轮询服务在独立进程，不受影响。")
                .setNegativeButton("取消", null)
                .setPositiveButton("重启", (dialog, which) -> restartSystemUi())
                .show();
    }

    private void restartSystemUi() {
        statusView.setText("正在重启系统界面…");
        new Thread(() -> {
            String message;
            try {
                ProcessBuilder builder = new ProcessBuilder(
                        "su", "-c", "pkill -f '[c]om.android.systemui'");
                builder.redirectErrorStream(true);
                Process process = builder.start();
                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append('\n');
                    }
                }
                int code = process.waitFor();
                String text = output.toString().trim();
                if (!text.isEmpty()) {
                    message = "重启失败：" + text;
                } else if (code == 0) {
                    message = "已重启系统界面（su 返回 0），请稍等几秒";
                } else {
                    message = "su 退出码 " + code + "（无输出，通常已发出重启信号）";
                }
            } catch (Exception error) {
                message = "重启失败：" + error.getMessage() + "（需要 root / su 授权）";
            }
            final String result = message;
            handler.post(() -> {
                AppConfig.setStatus(this, result);
                statusView.setText(AppConfig.status(this));
            });
        }, "aodbridge-restart").start();
    }

    private void saveDisplayFromUi() {
        AppConfig.saveDisplay(this, shiftSwitch.isChecked(),
                selectedDurationSec(), selectedLinesMask(), keepAodSwitch.isChecked(),
                hideFpSwitch.isChecked());
        AppConfig.setWindow(this,
                WINDOW_FROM_VALUES[windowFromSpinner.getSelectedItemPosition()],
                WINDOW_TO_VALUES[windowToSpinner.getSelectedItemPosition()]);
    }

    /** 账号禁用表：拉 /aod/accounts（中转 v0.4.1+），仅显示在 App 内，不进 AOD。 */
    private void refreshAccountTable() {
        if (accountTableLoading) {
            return;
        }
        accountTableLoading = true;
        new Thread(() -> {
            String error = null;
            org.json.JSONObject payload = null;
            try {
                payload = RelayClient.fetchAccounts(AppConfig.url(this), AppConfig.token(this));
            } catch (Exception fetchError) {
                error = fetchError.getMessage();
            }
            final org.json.JSONObject data = payload;
            final String failMessage = error;
            handler.post(() -> renderAccountTable(data, failMessage));
            accountTableLoading = false;
        }, "aodbridge-accounts").start();
    }

    private void renderAccountTable(org.json.JSONObject payload, String error) {
        LinearLayout card = (LinearLayout) ((ViewGroup) getWindow().getDecorView()
                .findViewWithTag("account_card"));
        if (card == null) {
            return;
        }
        card.removeAllViews();
        if (payload == null) {
            TextView note = text("账号禁用：加载失败（" + (error == null ? "未知错误" : error)
                    + "）", 13, palette.textSecondary);
            note.setPadding(dp(18), dp(14), dp(18), dp(14));
            card.addView(note);
            return;
        }
        org.json.JSONArray rows = payload.optJSONArray("disabled_accounts");
        int count = rows == null ? 0 : rows.length();
        TextView caption = text(count == 0
                ? "当前没有禁用或冷却中的账号"
                : "禁用 / 冷却中的账号（" + count + "）", 13, palette.textSecondary);
        caption.setPadding(dp(18), dp(12), dp(18), dp(4));
        card.addView(caption);
        if (count == 0) {
            return;
        }
        LinearLayout header = accountTableRow("账号", "区域", "状态", "剩余", "原因", true);
        card.addView(header);
        for (int i = 0; i < rows.length(); i++) {
            org.json.JSONObject row = rows.optJSONObject(i);
            if (row == null) {
                continue;
            }
            String nickname = row.optString("nickname", "");
            if (nickname.isEmpty()) {
                nickname = row.optString("uid", "?");
            }
            String realm = "cn".equals(row.optString("realm")) ? "国内" : "国际";
            boolean enabled = row.optBoolean("enabled", true);
            boolean cooling = row.optBoolean("inCooldown", false);
            String status;
            if (!enabled) {
                status = "已禁用";
            } else if (cooling) {
                status = "冷却 " + row.optInt("cooldownFor", 0) + "s";
            } else {
                status = "正常";
            }
            String reason = row.optString("lastError", "");
            card.addView(accountTableRow(nickname, realm, status,
                    row.optString("expiresIn", ""), reason, false));
        }
    }

    /** 表格行：竖屏适配，五列按权重分配；首行为表头。 */
    private LinearLayout accountTableRow(String name, String realm, String status,
                                         String remain, String reason, boolean header) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int color = header ? palette.textSecondary : palette.text;
        row.addView(accountCell(name, 0.24f, color, header));
        row.addView(accountCell(realm, 0.12f, color, header));
        row.addView(accountCell(status, 0.2f, color, header));
        row.addView(accountCell(remain, 0.14f, color, header));
        row.addView(accountCell(reason, 0.3f, color, header));
        row.setPadding(dp(14), dp(6), dp(14), dp(6));
        return row;
    }

    private TextView accountCell(String value, float weight, int color, boolean header) {
        TextView cell = text(value, header ? 11 : 12, color);
        cell.setSingleLine(true);
        cell.setEllipsize(android.text.TextUtils.TruncateAt.END);
        cell.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, weight));
        return cell;
    }

    /** 只发配置（不带 text）：hook 收到后只调整显示参数，不动文案。 */
    private void sendConfigBroadcast() {
        Intent intent = new Intent(Bridge.ACTION);
        intent.setPackage(Bridge.TARGET_PACKAGE);
        intent.putExtra(Bridge.EXTRA_SHIFT, AppConfig.shift(this));
        intent.putExtra(Bridge.EXTRA_DURATION_SEC, AppConfig.durationSec(this));
        intent.putExtra(Bridge.EXTRA_KEEP_AOD, AppConfig.keepAod(this));
        intent.putExtra(Bridge.EXTRA_HIDE_FP, AppConfig.hideFp(this));
        intent.putExtra(Bridge.EXTRA_WINDOW_FROM, AppConfig.windowFrom(this));
        intent.putExtra(Bridge.EXTRA_WINDOW_TO, AppConfig.windowTo(this));
        sendBroadcast(intent);
    }

    private int selectedDurationSec() {
        int index = durationSpinner == null ? -1 : durationSpinner.getSelectedItemPosition();
        if (index < 0 || index >= DURATION_VALUES.length) {
            return Bridge.DURATION_DEFAULT;
        }
        return DURATION_VALUES[index];
    }

    private int selectedLinesMask() {
        if (lineChecks == null) {
            return Bridge.LINE_ALL;
        }
        int mask = 0;
        for (int i = 0; i < lineChecks.length; i++) {
            if (lineChecks[i].isChecked()) {
                mask |= LINE_BITS[i];
            }
        }
        return mask;
    }

    private static int durationIndex(int seconds) {
        for (int i = 0; i < DURATION_VALUES.length; i++) {
            if (DURATION_VALUES[i] == seconds) {
                return i;
            }
        }
        return DURATION_VALUES.length - 1;
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(statusTicker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(statusTicker);
    }
}
