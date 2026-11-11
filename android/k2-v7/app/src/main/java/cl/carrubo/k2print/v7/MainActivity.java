package cl.carrubo.k2print.v7;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.sunmi.externalprinterlibrary.api.ConnectCallback;
import com.sunmi.externalprinterlibrary.api.SunmiPrinter;
import com.sunmi.externalprinterlibrary.api.SunmiPrinterApi;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** V7 diagnostico K2: solo un ticket fijo manual, SIN WebView, pagos, ni órdenes. */
public final class MainActivity extends Activity {
    private static final String PREFS = "k2_v7_diag_status";
    private static final String KEY = "last_attempt";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean working = new AtomicBoolean(false);
    private volatile boolean connected = false;
    private SunmiPrinterApi api;
    private TextView status;
    private Button printButton;

    private final ConnectCallback cb = new ConnectCallback() {
        @Override public void onFound() { show("Servicio de impresora SUNMI detectado."); }
        @Override public void onUnfound() { connected = false; show("Impresora SUNMI no detectada."); changeButtons(); }
        @Override public void onConnect() {
            connected = true; show("Impresora SUNMI conectada."); checkPrinter();
        }
        @Override public void onDisconnect() {
            connected = false; show("Impresora SUNMI desconectada."); changeButtons();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroller = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(32, 26, 32, 26);
        box.setBackgroundColor(Color.rgb(250, 244, 232));
        scroller.addView(box);

        TextView title = new TextView(this);
        title.setText("CARRUBO K2 — V7.1 DIAGNOSTICO");
        title.setTextSize(24); title.setTextColor(Color.rgb(135, 34, 24)); title.setGravity(Gravity.CENTER);
        box.addView(title);

        TextView note = new TextView(this);
        note.setText("APP DE DIAGNOSTICO · SIN COBRO · SIN BOLETA SII\n\nNO ENVIA COMANDAS A COCINA\nNO MODIFICA LA V6\n\nSolamente imprime un ticket fijo al pulsar el botón y confirmar.");
        note.setTextSize(17); note.setPadding(0, 20, 0, 30);
        box.addView(note);

        status = new TextView(this);
        status.setTextSize(19); status.setTextColor(Color.BLACK);
        status.setText("Conectando impresora SUNMI...");
        box.addView(status);

        Button check = new Button(this);
        check.setText("Verificar estado de impresora");
        check.setOnClickListener(v -> checkPrinter());
        box.addView(check);

        printButton = new Button(this);
        printButton.setText("PRUEBA V6: TEXTO + CORTE (NO COBRA)");
        printButton.setOnClickListener(v -> confirmPrint());
        box.addView(printButton);

        Button reset = new Button(this);
        reset.setText("Restablecer bloqueo manualmente");
        reset.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle("Primero revisa el papel")
            .setMessage("Si la impresión quedó en estado desconocido, NO repitas sin revisar si salió papel. Esto solo permite una nueva prueba por decisión del operador.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Restablecer", (d, w) -> {
                if (!working.get()) {
                    prefs().edit().putString(KEY, "idle").commit();
                    show("Bloqueo local restablecido.");
                    changeButtons();
                }
            }).show());
        box.addView(reset);
        setContentView(scroller);
        changeButtons();
        connect();
    }

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }
    private String state() { return prefs().getString(KEY, "idle"); }
    private void show(String m) {
        ui.post(() -> {
            if (status != null) status.setText(m + "\n\nEstado persistente: " + state());
        });
    }
    private void changeButtons() {
        ui.post(() -> { if (printButton != null)
            printButton.setEnabled(connected && !working.get() && "idle".equals(state())); });
    }

    private void connect() {
        try {
            api = SunmiPrinterApi.getInstance();
            // Nombre de enum confirmado en el APK V6 original del K2.
            api.setPrinter(SunmiPrinter.SunmiYKPriner, new String[0]);
            api.connectPrinter(this, cb);
        } catch (Exception e) {
            connected = false; show("Error inicializando SUNMI: " + e.getClass().getSimpleName());
        }
        changeButtons();
    }

    private void checkPrinter() {
        if (!connected || api == null) { show("No hay conexion con el servicio SUNMI."); changeButtons(); return; }
        io.execute(() -> {
            try {
                int v = api.getPrinterStatus();
                show(v == 0 ? "Impresora normal: lista." : "Impresora NO lista, codigo " + v);
            } catch (Exception e) { show("Error leyendo estado: " + e.getClass().getSimpleName()); }
            changeButtons();
        });
    }

    private void confirmPrint() {
        if (!connected || working.get() || !"idle".equals(state())) return;
        new AlertDialog.Builder(this)
            .setTitle("Prueba FISICA de impresión")
            .setMessage("¿Imprimir UN ticket de prueba en la impresora integrada? No cobrará dinero ni enviará pedidos.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Imprimir una vez", (d, w) -> beginPrint())
            .show();
    }

    private void beginPrint() {
        if (!connected || !"idle".equals(state()) || !working.compareAndSet(false, true)) return;
        prefs().edit().putString(KEY, "pending").commit();
        changeButtons();
        show("Prueba iniciada. Sin reintentos automáticos.");

        ui.postDelayed(() -> {
            if ("pending".equals(state())) {
                prefs().edit().putString(KEY, "unknown").commit();
                show("SIN CONFIRMACION EN 25 SEGUNDOS: verificar el papel antes de restablecer.");
            }
        }, 25000);

        io.execute(() -> {
            try {
                if (api.getPrinterStatus() != 0) {
                    prefs().edit().putString(KEY, "unknown").commit();
                    show("Impresora no lista. Prueba suspendida. Verificar papel.");
                    return;
                }
                // Mismo flujo SDK identificado en la V6: printText seguido de cutPaper.
                // No usar buffer transaccional (no presente en el APK V6).
                show("PASO 1 - printText");
                api.printText("CARRUBO - K2 V7.1 TEST\nNO TRIBUTARIO\nSIN COBRO\nNO SE ENVIO A COCINA\n---------------------\nTEST DIAGNOSTICO, NO VENTA\n\n");
                show("PASO 2 - cutPaper");
                api.cutPaper(1, 0);
                prefs().edit().putString(KEY_STATE, "unknown").commit();
                result = "SDK acepto printText + cutPaper. Comprueba el papel; SIN reintento automatico.";
            } catch (Exception e) {
                prefs().edit().putString(KEY, "unknown").commit();
                show("Estado ambiguo: " + e.getClass().getSimpleName() + ". Revisar papel.");
            } finally {
                working.set(false);
                changeButtons();
            }
        });
    }
    @Override protected void onDestroy() {
        try { if (api != null) api.disconnectPrinter(this); } catch (Exception ignored) {}
        io.shutdown();
        super.onDestroy();
    }
}
