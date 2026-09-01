package com.mggx.ubuntu;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements TerminalSessionClient, TerminalViewClient {
    private static final String TAG = "MGGXUbuntu";

    private TerminalView terminalView;
    private TerminalSession session;
    private TextView status;
    private ProgressBar progress;
    private Button ctrlButton;
    private Button altButton;
    private Button wakeButton;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean installing;
    private boolean ctrlDown;
    private boolean altDown;
    private boolean wakeEnabled;
    private int fontSizePx;
    private volatile String installStage = "Preparando Ubuntu";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        terminalView = findViewById(R.id.terminal);
        status = findViewById(R.id.status);
        progress = findViewById(R.id.progress);
        ctrlButton = findViewById(R.id.keyCtrl);
        altButton = findViewById(R.id.keyAlt);
        wakeButton = findViewById(R.id.wakelock);

        terminalView.setTerminalViewClient(this);
        fontSizePx = Math.round(14f * getResources().getDisplayMetrics().scaledDensity);
        terminalView.setTextSize(fontSizePx);
        terminalView.requestFocus();

        wireExtraKeys();
        findViewById(R.id.menu).setOnClickListener(v -> showMenu());
        wakeButton.setOnClickListener(v -> toggleWakeLock());

        ensureStoragePermissionThenStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            status.setText("Necesito acceso a todos los archivos");
        } else if (session == null && !installing) {
            ensureStoragePermissionThenStart();
        }
    }

    private void ensureStoragePermissionThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            status.setText("Concedé “Acceso a todos los archivos” para usar /sdcard desde Ubuntu");
            openAllFilesSettings();
            return;
        }

        UbuntuInstaller.recoverIfNeeded(this);
        if (UbuntuInstaller.isInstalled(this)) startUbuntu();
        else installUbuntu();
    }

    private void installUbuntu() {
        if (installing) return;
        installing = true;
        progress.setVisibility(ProgressBar.VISIBLE);
        progress.setIndeterminate(true);
        status.setText("Preparando Ubuntu…");

        worker.execute(() -> {
            try {
                UbuntuInstaller.install(this, (stage, percent) -> {
                    installStage = stage;
                    runOnUiThread(() -> ifAlive(() -> {
                        status.setText(stage);
                        if (percent >= 0) {
                            progress.setIndeterminate(false);
                            progress.setProgress(percent);
                        } else {
                            progress.setIndeterminate(true);
                        }
                    }));
                });
                runOnUiThread(() -> ifAlive(() -> {
                    installing = false;
                    progress.setVisibility(ProgressBar.GONE);
                    startUbuntu();
                }));
            } catch (Throwable e) {
                Log.e(TAG, "Ubuntu install failed at " + installStage, e);
                runOnUiThread(() -> ifAlive(() -> showInstallError(e)));
            }
        });
    }

    /** Evita tocar vistas o arrancar sesiones después de que la Activity ya se destruyó. */
    private void ifAlive(Runnable action) {
        if (!isFinishing() && !isDestroyed()) action.run();
    }

    private String appVersionLabel() {
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            return info.versionName + " (" + code + ")";
        } catch (PackageManager.NameNotFoundException e) {
            return "desconocida";
        }
    }

    private void showInstallError(Throwable error) {
        installing = false;
        progress.setVisibility(ProgressBar.GONE);
        String details = "Etapa: " + installStage
                + "\nApp: " + appVersionLabel()
                + "\nAndroid: " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT
                + "\nABI: " + (Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "desconocida")
                + "\n\n" + error;
        status.setText("Error instalando Ubuntu: " + error.getMessage());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("No se pudo instalar Ubuntu")
                .setMessage(details)
                .setPositiveButton("Reintentar", (d, w) -> installUbuntu())
                .setNegativeButton("Cerrar", null)
                .setNeutralButton("Copiar", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            StringWriter sw = new StringWriter();
            error.printStackTrace(new PrintWriter(sw));
            String full = details + "\n\n" + sw;
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("MGGX Ubuntu error", full));
            Toast.makeText(this, "Detalles copiados", Toast.LENGTH_SHORT).show();
        }));
        dialog.show();
    }

    private void startUbuntu() {
        if (session != null && session.isRunning()) return;

        String libDir = getApplicationInfo().nativeLibraryDir;
        File proot = new File(libDir, "libproot.so");
        File prootLoader = new File(libDir, "libproot-loader.so");
        if (!proot.isFile() || !prootLoader.isFile()) {
            status.setText("Faltan componentes PRoot en el APK");
            return;
        }

        File root = UbuntuInstaller.rootfsDir(this);
        String files = getFilesDir().getAbsolutePath();
        String storage = Environment.getExternalStorageDirectory().getAbsolutePath();

        String[] args = new String[]{
                "proot",
                "--link2symlink",
                "-0",
                "-r", root.getAbsolutePath(),
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "-b", storage + ":/sdcard",
                "-b", files + ":/host",
                "-w", "/root",
                "/usr/bin/env", "-i",
                "HOME=/root",
                "USER=root",
                "LOGNAME=root",
                "SHELL=/bin/bash",
                "TERM=xterm-256color",
                "COLORTERM=truecolor",
                "LANG=C.UTF-8",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "/bin/bash", "--login"
        };

        String[] env = new String[]{
                "HOME=" + files,
                "TMPDIR=" + getCacheDir().getAbsolutePath(),
                "LD_LIBRARY_PATH=" + libDir,
                "PROOT_LOADER=" + prootLoader.getAbsolutePath(),
                "PROOT_TMP_DIR=" + getCacheDir().getAbsolutePath(),
                "PATH=/system/bin:/system/xbin"
        };

        try {
            session = new TerminalSession(proot.getAbsolutePath(), files, args, env, 5000, this);
            terminalView.attachSession(session);
            terminalView.requestFocus();
            status.setText("Ubuntu 26.04 • ARM64 • /sdcard disponible");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start Ubuntu", t);
            status.setText("No se pudo iniciar PRoot: " + t.getMessage());
        }
    }

    private void wireExtraKeys() {
        findViewById(R.id.keyEsc).setOnClickListener(v -> write("\u001b"));
        ctrlButton.setOnClickListener(v -> {
            ctrlDown = !ctrlDown;
            ctrlButton.setText(ctrlDown ? "CTRL●" : "CTRL");
        });
        altButton.setOnClickListener(v -> {
            altDown = !altDown;
            altButton.setText(altDown ? "ALT●" : "ALT");
        });
        findViewById(R.id.keyTab).setOnClickListener(v -> write("\t"));
        findViewById(R.id.keyLeft).setOnClickListener(v -> handleKey(KeyEvent.KEYCODE_DPAD_LEFT));
        findViewById(R.id.keyUp).setOnClickListener(v -> handleKey(KeyEvent.KEYCODE_DPAD_UP));
        findViewById(R.id.keyDown).setOnClickListener(v -> handleKey(KeyEvent.KEYCODE_DPAD_DOWN));
        findViewById(R.id.keyRight).setOnClickListener(v -> handleKey(KeyEvent.KEYCODE_DPAD_RIGHT));
        findViewById(R.id.keyHome).setOnClickListener(v -> write("\u001b[H"));
        findViewById(R.id.keyEnd).setOnClickListener(v -> write("\u001b[F"));
        findViewById(R.id.keyPgUp).setOnClickListener(v -> write("\u001b[5~"));
        findViewById(R.id.keyPgDn).setOnClickListener(v -> write("\u001b[6~"));
        findViewById(R.id.keyPipe).setOnClickListener(v -> write("|"));
        findViewById(R.id.keySlash).setOnClickListener(v -> write("/"));
        findViewById(R.id.keyDash).setOnClickListener(v -> write("-"));
    }

    private void handleKey(int keyCode) {
        if (session == null) return;
        terminalView.handleKeyCode(keyCode, 0);
        terminalView.requestFocus();
    }

    private void write(String text) {
        if (session == null) return;
        session.write(text);
        terminalView.requestFocus();
    }

    private void showMenu() {
        PopupMenu menu = new PopupMenu(this, findViewById(R.id.menu));
        menu.getMenu().add("Permisos Android…");
        menu.getMenu().add("Reiniciar sesión Ubuntu");
        menu.getMenu().add("Reinstalar Ubuntu");
        menu.getMenu().add("Abrir permiso de todos los archivos");
        menu.setOnMenuItemClickListener(item -> {
            String title = item.getTitle().toString();
            if (title.startsWith("Permisos")) showPermissionDialog();
            else if (title.startsWith("Reiniciar")) restartSession();
            else if (title.startsWith("Reinstalar")) confirmReinstall();
            else openAllFilesSettings();
            return true;
        });
        menu.show();
    }

    private void showPermissionDialog() {
        String[] items = new String[]{"Cámara", "Micrófono", "Ubicación precisa", "Notificaciones"};
        new AlertDialog.Builder(this)
                .setTitle("Permisos Android")
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0: requestPermissions(new String[]{Manifest.permission.CAMERA}, 200); break;
                        case 1: requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 201); break;
                        case 2: requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 202); break;
                        case 3:
                            if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 203);
                            else Toast.makeText(this, "No hace falta en esta versión de Android", Toast.LENGTH_SHORT).show();
                            break;
                    }
                })
                .show();
    }

    private void restartSession() {
        if (session != null) session.finishIfRunning();
        session = null;
        terminalView.attachSession(null);
        startUbuntu();
    }

    private void confirmReinstall() {
        new AlertDialog.Builder(this)
                .setTitle("Reinstalar Ubuntu")
                .setMessage("Esto borra el rootfs interno de Ubuntu. Los archivos de /sdcard no se borran.")
                .setPositiveButton("Reinstalar", (d, w) -> {
                    if (session != null) session.finishIfRunning();
                    session = null;
                    terminalView.attachSession(null);
                    worker.execute(() -> {
                        UbuntuInstaller.removeInstallation(this);
                        runOnUiThread(() -> ifAlive(this::installUbuntu));
                    });
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void openAllFilesSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    private void toggleWakeLock() {
        wakeEnabled = !wakeEnabled;
        if (wakeEnabled) {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 203);
            }
            Intent intent = new Intent(this, WakeLockService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
            wakeButton.setText("⚡●");
            Toast.makeText(this, "Wake lock encendido", Toast.LENGTH_SHORT).show();
        } else {
            stopService(new Intent(this, WakeLockService.class));
            wakeButton.setText("⚡");
            Toast.makeText(this, "Wake lock apagado", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override public void onTextChanged(TerminalSession changedSession) { terminalView.onScreenUpdated(); }
    @Override public void onTitleChanged(TerminalSession changedSession) {
        String title = changedSession.getTitle();
        if (title != null && !title.isEmpty()) ((TextView) findViewById(R.id.title)).setText(title);
    }
    @Override public void onSessionFinished(TerminalSession finishedSession) { status.setText("Sesión finalizada"); }
    @Override public void onCopyTextToClipboard(TerminalSession s, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("terminal", text));
    }
    @Override public void onPasteTextFromClipboard(TerminalSession s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm.hasPrimaryClip() && cm.getPrimaryClip() != null) {
            CharSequence text = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            if (text != null && s.getEmulator() != null) s.getEmulator().paste(text.toString());
        }
    }
    @Override public void onBell(TerminalSession s) { terminalView.performHapticFeedback(1); }
    @Override public void onColorsChanged(TerminalSession s) { terminalView.invalidate(); }
    @Override public void onTerminalCursorStateChange(boolean state) { terminalView.invalidate(); }
    @Override public Integer getTerminalCursorStyle() { return null; }

    @Override public float onScale(float scale) {
        int next = Math.max(Math.round(9 * getResources().getDisplayMetrics().scaledDensity),
                Math.min(Math.round(28 * getResources().getDisplayMetrics().scaledDensity), Math.round(fontSizePx * scale)));
        if (next != fontSizePx) {
            fontSizePx = next;
            terminalView.setTextSize(fontSizePx);
        }
        return 1.0f;
    }
    @Override public void onSingleTapUp(MotionEvent e) {
        terminalView.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT);
    }
    @Override public boolean shouldBackButtonBeMappedToEscape() { return false; }
    @Override public boolean shouldEnforceCharBasedInput() { return true; }
    @Override public boolean shouldUseCtrlSpaceWorkaround() { return false; }
    @Override public boolean isTerminalViewSelected() { return terminalView.hasFocus(); }
    @Override public void copyModeChanged(boolean copyMode) {}
    @Override public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession s) { return false; }
    @Override public boolean onKeyUp(int keyCode, KeyEvent e) { return false; }
    @Override public boolean onLongPress(MotionEvent event) { return false; }
    @Override public boolean readControlKey() { return ctrlDown; }
    @Override public boolean readAltKey() { return altDown; }
    @Override public boolean readShiftKey() { return false; }
    @Override public boolean readFnKey() { return false; }
    @Override public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession s) { return false; }
    @Override public void onEmulatorSet() {}

    @Override public void logError(String tag, String message) { Log.e(tag, message); }
    @Override public void logWarn(String tag, String message) { Log.w(tag, message); }
    @Override public void logInfo(String tag, String message) { Log.i(tag, message); }
    @Override public void logDebug(String tag, String message) { Log.d(tag, message); }
    @Override public void logVerbose(String tag, String message) { Log.v(tag, message); }
    @Override public void logStackTraceWithMessage(String tag, String message, Exception e) { Log.e(tag, message, e); }
    @Override public void logStackTrace(String tag, Exception e) { Log.e(tag, "Terminal error", e); }
}
