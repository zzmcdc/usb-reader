package dev.usbreader;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.*;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.text.*;
import android.view.*;
import android.widget.*;
import dev.usbreader.core.*;
import dev.usbreader.usb.UsbDisk;
import java.util.*;

public final class MainActivity extends Activity implements ReaderModel.Listener {
    private static final String USB_PERMISSION = "dev.usbreader.USB_PERMISSION";
    private static final int OPEN_IMAGE = 10, EXPORT_FILE = 11;
    private static final int INK = 0xff172b3a, MUTED = 0xff637584, TEAL = 0xff087f8c, BG = 0xfff5f7fa;
    private ReaderModel model;
    private UsbManager usb;
    private TextView progressText;
    private ProgressBar progressBar;
    private EntryAdapter adapter;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (USB_PERMISSION.equals(action)) permissionResult();
            else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null && (device.getDeviceName().equals(model.deviceName) ||
                    (model.pendingTarget != null && device.equals(model.pendingTarget.device)))) {
                    model.disconnect("U盘已拔出，请重新连接");
                } else if (!model.busy) changed();
            } else if (!model.busy) changed();
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        usb = (UsbManager) getSystemService(USB_SERVICE);
        Object retained = getLastNonConfigurationInstance();
        model = retained instanceof ReaderModel ? (ReaderModel) retained : new ReaderModel(this);
        model.listener = this;
        IntentFilter filter = new IntentFilter(USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerLegacyReceiver(filter);
        if (Build.VERSION.SDK_INT >= 33)
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
        changed();
    }
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerLegacyReceiver(IntentFilter filter) {
        // Called only on API 26–32, before the exported flags exist. A signature
        // permission provides the equivalent protection (as in ContextCompat).
        registerReceiver(receiver, filter, "dev.usbreader.INTERNAL_USB", null);
    }
    @Override protected void onResume() {
        super.onResume();
        if (model.pendingTarget != null && usb.hasPermission(model.pendingTarget.device)) permissionResult();
        else if (model.deviceName != null && !usb.getDeviceList().containsKey(model.deviceName)) model.disconnect("U盘已断开");
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); if (!model.busy) changed(); }
    @Override public Object onRetainNonConfigurationInstance() { model.listener = null; return model; }
    @Override protected void onDestroy() {
        unregisterReceiver(receiver); model.listener = null;
        if (!isChangingConfigurations()) model.dispose();
        super.onDestroy();
    }
    @SuppressWarnings("deprecation") @Override public void onBackPressed() { back(); }
    private void back() {
        if (model.busy) { Toast.makeText(this, "请等待当前操作，或点击取消 / 断开", Toast.LENGTH_SHORT).show(); return; }
        if (model.selected != null) model.up(); else finish();
    }
    private int dp(float value) { return (int) (getResources().getDisplayMetrics().density * value + .5f); }
    private TextView text(String value, float size, int color) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); return v;
    }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable;
    }
    private Button button(String label, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setTextSize(13); b.setAllCaps(false); b.setTextColor(TEAL);
        b.setMinWidth(0); b.setMinimumWidth(0); b.setOnClickListener(v -> action.run()); return b;
    }
    private void section(LinearLayout root, String title) {
        TextView heading = text(title, 16, INK); heading.setTypeface(null, Typeface.BOLD); heading.setPadding(0, dp(16), 0, dp(10)); root.addView(heading);
    }
    private void weighted(LinearLayout row, View child) { row.addView(child, new LinearLayout.LayoutParams(0, -2, 1)); }
    private boolean compact() { return getResources().getConfiguration().screenHeightDp < 500; }
    @Override public void changed() {
        if (isFinishing() || isDestroyed()) return;
        if (model.busy) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = column(); root.setBackgroundColor(BG); root.setPadding(dp(20), dp(8), dp(20), dp(8));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(dp(20) + insets.getSystemWindowInsetLeft(), dp(8) + insets.getSystemWindowInsetTop(),
                    dp(20) + insets.getSystemWindowInsetRight(), dp(8) + insets.getSystemWindowInsetBottom()); return insets;
        });
        LinearLayout header = row();
        TextView brand = text("U盘通读", compact() ? 20 : 26, INK); brand.setTypeface(null, Typeface.BOLD); weighted(header, brand);
        header.addView(button("使用说明", this::help)); root.addView(header);
        TextView subtitle = text("USB READER   /   无需 ROOT · 只读访问", 11, MUTED);
        subtitle.setLetterSpacing(.07f); subtitle.setPadding(0, 0, 0, dp(16)); if (!compact()) root.addView(subtitle);

        LinearLayout card = column(); card.setPadding(dp(18), dp(compact() ? 8 : 16), dp(18), dp(compact() ? 8 : 16)); card.setBackground(shape(INK, 18));
        TextView name = text(model.title, compact() ? 16 : 18, Color.WHITE); name.setTypeface(null, Typeface.BOLD); name.setMaxLines(2);
        TextView detail = text(model.capacity > 0 ? formatSize(model.capacity) + "  ·  只读连接" : "Windows / Linux 多格式读取", 12, 0xffa9cad2);
        if (compact()) { LinearLayout summary = row(); weighted(summary, name); summary.addView(detail); card.addView(summary); }
        else { card.addView(name); detail.setPadding(0, dp(7), 0, dp(5)); card.addView(detail); }
        TextView status = text(model.status, 13, 0xffe3eff2); status.setMaxLines(compact() ? 1 : 3); card.addView(status); root.addView(card);

        LinearLayout actions = row();
        Button scan = button(model.selected == null ? "刷新设备" : "返回上一级", () -> { if (model.selected == null) changed(); else model.up(); });
        scan.setEnabled(!model.busy); weighted(actions, scan);
        Button image = button("打开镜像", this::openImage); image.setEnabled(!model.busy); weighted(actions, image);
        if (model.capacity > 0 || model.busy) weighted(actions, button("断开", () -> model.disconnect("已断开，可移除 U盘")));
        root.addView(actions);

        progressText = null; progressBar = null;
        if (model.busy) {
            progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            progressBar.setIndeterminate(!model.exporting); progressBar.setMax(1000); root.addView(progressBar);
            if (model.exporting) {
                LinearLayout p = row(); progressText = text("", 12, MUTED); weighted(p, progressText);
                p.addView(button("取消导出", () -> { model.cancelExport.set(true); progressText.setText("正在取消…"); })); root.addView(p); progress();
            }
        }
        if (model.selected != null) showFiles(root);
        else if (model.volumes.length > 0) showVolumes(root);
        else showDevices(root);
        setContentView(root); root.requestApplyInsets();
        if (model.message != null) {
            String message = model.message; model.message = null;
            new AlertDialog.Builder(this).setTitle("操作提示").setMessage(message).setPositiveButton("知道了", null).show();
        }
        if (model.preview != null) {
            String preview = model.preview; model.preview = null;
            TextView content = text(preview, 14, INK); content.setTypeface(Typeface.MONOSPACE); content.setTextIsSelectable(true); content.setPadding(dp(20), dp(12), dp(20), dp(12));
            ScrollView scroll = new ScrollView(this); scroll.addView(content);
            new AlertDialog.Builder(this).setTitle("文件预览 · UTF-8 / 十六进制").setView(scroll).setPositiveButton("关闭", null).show();
        }
    }
    @Override public void progress() {
        if (progressText != null) progressText.setText(getString(R.string.export_progress, formatSize(model.progressBytes), formatSize(model.progressTotal)));
        if (progressBar != null && model.exporting)
            progressBar.setProgress(model.progressTotal == 0 ? 1000 : (int) (1000.0 * model.progressBytes / model.progressTotal));
    }
    private void showDevices(LinearLayout root) {
        section(root, "存储设备");
        ScrollView scroll = new ScrollView(this); LinearLayout content = column(); scroll.addView(content);
        List<UsbDisk.Target> devices = UsbDisk.targets(usb);
        if (devices.isEmpty()) {
            TextView symbol = text("⇅", 62, TEAL); symbol.setGravity(Gravity.CENTER); symbol.setPadding(0, dp(16), 0, dp(8)); content.addView(symbol);
            TextView empty = text("连接你的 U盘", 21, INK); empty.setGravity(Gravity.CENTER); empty.setTypeface(null, Typeface.BOLD); content.addView(empty);
            TextView hint = text("使用 OTG 转接头连接 U盘，然后点击刷新设备。\n首次连接时，请允许应用访问 USB 设备。", 14, MUTED);
            hint.setGravity(Gravity.CENTER); hint.setLineSpacing(dp(5), 1); hint.setPadding(dp(12), dp(12), dp(12), dp(22)); content.addView(hint);
            if (!usb.getDeviceList().isEmpty()) {
                TextView other = text("已发现 USB 设备，但没有可用的 SCSI / Bulk-Only 存储接口。仅支持 UAS 的设备暂不可读取。", 13, MUTED);
                content.addView(other);
            }
        }
        for (UsbDisk.Target device : devices) {
            LinearLayout item = column(); item.setPadding(dp(16), dp(12), dp(16), dp(12)); item.setBackground(shape(Color.WHITE, 12));
            item.addView(text(device.name(), 16, INK));
            item.addView(text(String.format(Locale.ROOT, "USB %04X:%04X", device.device.getVendorId(), device.device.getProductId()), 12, MUTED));
            LinearLayout actions = row();
            Button connect = button("连接并读取", () -> requestConnect(device, 0)); connect.setEnabled(!model.busy); weighted(actions, connect);
            Button lun = button("选择逻辑单元", () -> chooseLun(device)); lun.setEnabled(!model.busy); weighted(actions, lun); item.addView(actions);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(10); content.addView(item, params);
        }
        TextView foot = text("FAT / exFAT / NTFS / ext / XFS / Btrfs / F2FS\n更多格式与兼容范围请查看使用说明。\n支持多分区浏览、文件预览和保存到手机。", 12, MUTED);
        foot.setLineSpacing(dp(4), 1); foot.setPadding(0, dp(18), 0, dp(12)); content.addView(foot);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }
    private void showVolumes(LinearLayout root) {
        section(root, "选择分区 · " + model.volumes.length);
        ListView list = new ListView(this); list.setDividerHeight(dp(8)); list.setDivider(null);
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return model.volumes.length; }
            @Override public Object getItem(int p) { return model.volumes[p]; }
            @Override public long getItemId(int p) { return p; }
            @Override public View getView(int p, View convert, android.view.ViewGroup parent) {
                Volume v = model.volumes[p]; LinearLayout item = column(); item.setPadding(dp(16), dp(16), dp(16), dp(16)); item.setBackground(shape(Color.WHITE, 12));
                item.addView(text("分区 " + (p + 1) + "   " + v.type.toUpperCase(Locale.ROOT), 17, v.readable() ? TEAL : MUTED));
                item.addView(text(formatSize(v.length) + "  ·  " + (v.readable() ? "可读取  ›" : "暂不支持 / 无法读取"), 13, MUTED));
                item.addView(text(v.description, 11, MUTED)); return item;
            }
        });
        list.setOnItemClickListener((parent, view, position, id) -> { if (!model.busy) model.choose(model.volumes[position]); });
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
    }
    private void showFiles(LinearLayout root) {
        StringBuilder path = new StringBuilder(model.selected.type.toUpperCase(Locale.ROOT));
        for (ReaderModel.Folder folder : model.folders) path.append(" / ").append(folder.name);
        TextView breadcrumb = text(path.toString(), 13, TEAL); breadcrumb.setMaxLines(2); breadcrumb.setPadding(0, dp(6), 0, dp(8)); root.addView(breadcrumb);
        EditText search = new EditText(this); search.setSingleLine(true); search.setTextSize(14); search.setHint("筛选当前目录…");
        search.setContentDescription("筛选当前目录"); root.addView(search);
        FrameLayout frame = new FrameLayout(this); ListView list = new ListView(this); list.setDividerHeight(1);
        TextView empty = text("此目录为空，或没有匹配的文件", 14, MUTED); empty.setGravity(Gravity.CENTER);
        frame.addView(empty, new FrameLayout.LayoutParams(-1, -1)); frame.addView(list, new FrameLayout.LayoutParams(-1, -1)); list.setEmptyView(empty);
        adapter = new EntryAdapter(model.entries); list.setAdapter(adapter); list.setEnabled(!model.busy);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (model.busy) return;
            Entry entry = adapter.items.get(position); if (entry.directory) model.enter(entry); else fileActions(entry);
        });
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { adapter.filter(s.toString()); }
            public void afterTextChanged(Editable e) {}
        });
        root.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1));
    }
    private final class EntryAdapter extends BaseAdapter {
        final Entry[] all; final List<Entry> items = new ArrayList<>();
        EntryAdapter(Entry[] all) { this.all = all; Collections.addAll(items, all); }
        void filter(String term) { items.clear(); for (Entry e : all) if (e.name.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT))) items.add(e); notifyDataSetChanged(); }
        public int getCount() { return items.size(); }
        public Object getItem(int p) { return items.get(p); }
        public long getItemId(int p) { return p; }
        public View getView(int p, View convert, android.view.ViewGroup parent) {
            LinearLayout item;
            if (convert instanceof LinearLayout) item = (LinearLayout) convert;
            else {
                item = row(); item.setPadding(dp(4), dp(compact() ? 8 : 14), dp(4), dp(compact() ? 8 : 14));
                TextView icon = text("", 22, TEAL); icon.setGravity(Gravity.CENTER); item.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));
                LinearLayout labels = column(); labels.addView(text("", 15, INK)); labels.addView(text("", 12, MUTED));
                item.addView(labels, new LinearLayout.LayoutParams(0, -2, 1)); item.addView(text("›", 22, MUTED));
            }
            Entry e = items.get(p); ((TextView) item.getChildAt(0)).setText(e.directory ? "▰" : "≡");
            LinearLayout labels = (LinearLayout) item.getChildAt(1);
            ((TextView) labels.getChildAt(0)).setText(e.name);
            ((TextView) labels.getChildAt(1)).setText(e.directory ? "文件夹" : (e.regular ? formatSize(e.size) : "链接 / 特殊文件"));
            item.setContentDescription(e.name); return item;
        }
    }
    private void fileActions(Entry entry) {
        if (!entry.regular) { model.tell("当前版本仅导出普通文件；不跟随符号链接，也不读取设备文件。"); return; }
        new AlertDialog.Builder(this).setTitle(entry.name).setItems(new String[] {"预览内容（前 64 KiB）", "导出到手机…"}, (dialog, which) -> {
            if (which == 0) model.preview(entry);
            else {
                model.prepareExport(entry);
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream");
                intent.putExtra(Intent.EXTRA_TITLE, entry.name.replace('/', '_').replace('\\', '_'));
                try { startActivityForResult(intent, EXPORT_FILE); } catch (ActivityNotFoundException e) { model.pendingExport = null; model.tell("系统文件选择器不可用"); }
            }
        }).setNegativeButton("取消", null).show();
    }
    private void requestConnect(UsbDisk.Target target, int lun) {
        if (model.busy) return;
        if (usb.hasPermission(target.device)) { model.connect(target, lun); return; }
        model.pendingTarget = target; model.pendingLun = lun; model.busy = true; model.status = "等待 USB 访问授权…"; changed();
        // Immutable callback carries its own action; consult UsbManager for the actual grant.
        Intent callback = new Intent(USB_PERMISSION).setPackage(getPackageName());
        PendingIntent pending = PendingIntent.getBroadcast(this, target.device.getDeviceId(), callback, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try { usb.requestPermission(target.device, pending); }
        catch (RuntimeException e) { model.pendingTarget = null; model.busy = false; model.tell(ReaderModel.error(e)); }
    }
    private void permissionResult() {
        UsbDisk.Target target = model.pendingTarget;
        if (target == null) return;
        model.pendingTarget = null; model.busy = false;
        if (usb.hasPermission(target.device)) model.connect(target, model.pendingLun);
        else { model.status = "未获得 USB 权限，可点击连接重新授权"; changed(); }
    }
    private void chooseLun(UsbDisk.Target target) {
        EditText value = new EditText(this); value.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); value.setText("0"); value.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle("逻辑单元 LUN（0–15）").setMessage("普通 U盘使用 0。多槽读卡器可能使用其他编号。").setView(value)
            .setPositiveButton("连接", (dialog, which) -> {
                try { int lun = Integer.parseInt(value.getText().toString()); if (lun < 0 || lun > 15) throw new NumberFormatException(); requestConnect(target, lun); }
                catch (NumberFormatException e) { model.tell("请输入 0–15 的整数"); }
            }).setNegativeButton("取消", null).show();
    }
    private void openImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try { startActivityForResult(intent, OPEN_IMAGE); } catch (ActivityNotFoundException e) { model.tell("系统文件选择器不可用"); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) { if (request == EXPORT_FILE) model.pendingExport = null; return; }
        Uri uri = data.getData();
        if (request == EXPORT_FILE) model.export(uri);
        else if (request == OPEN_IMAGE) {
            String name = "磁盘镜像";
            try (android.database.Cursor cursor = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
            } catch (RuntimeException ignored) {}
            model.image(uri, name);
        }
    }
    private void help() {
        new AlertDialog.Builder(this).setTitle("读取更多格式的 U盘")
            .setMessage("1. 使用支持 OTG / USB Host 的 Android 8.0 或更新设备。\n2. 插入 U盘，连接并允许 USB 访问。\n3. 选择分区、进入目录，点击文件预览或导出。\n\nWindows：FAT12/16/32、exFAT、NTFS、ReFS（实验性）。\nLinux：ext2/3/4、XFS、Btrfs、F2FS、ZFS、JFS、ReiserFS、NILFS2、Minix。\n镜像与其他格式：ISO9660、UDF、SquashFS、EROFS、ROMFS、HFS/HFS+、UFS。\n\n只读、无需 root，可读取 MBR / GPT 分区和原始镜像。格式支持不等于所有版本和特性均受支持。ReFS 和 ZFS 尤其受版本、卷特性限制；未验证的特性不能保证读取。\n\n设备须提供 SCSI Bulk-Only 接口。不支持 UAS-only、BitLocker/LUKS 等加密卷、LVM、跨设备 RAID/存储池；不能替代系统挂载。镜像须为可随机读取的本地原始镜像。\n\n若系统正在使用 U盘，请先在系统存储设置中弹出，再连接应用。请保持应用在前台完成导出。\n\n开源许可证随应用附带。")
            .setPositiveButton("知道了", null).setNeutralButton("开源许可", (dialog, which) -> licenses()).show();
    }
    private void licenses() {
        try (java.io.InputStream in = getAssets().open("licenses.txt")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            TextView view = text(out.toString("UTF-8"), 12, INK); view.setTextIsSelectable(true); view.setPadding(dp(16), dp(16), dp(16), dp(16));
            ScrollView scroll = new ScrollView(this); scroll.addView(view);
            new AlertDialog.Builder(this).setTitle("开源许可").setView(scroll).setPositiveButton("关闭", null).show();
        } catch (java.io.IOException e) { model.tell(ReaderModel.error(e)); }
    }
    static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KiB", "MiB", "GiB", "TiB", "PiB", "EiB"}; double value = bytes; int i = -1;
        do { value /= 1024; i++; } while (value >= 1024 && i < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", value, units[i]);
    }
}
