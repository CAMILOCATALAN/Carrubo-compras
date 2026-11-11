package cl.carrubo.k2print.v7;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.sunmi.extprinterservice.ExtPrinterService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** V7 diagnóstico aislado: no consulta órdenes, no cobra, no usa el WebView de V6. */
public final class MainActivity extends Activity {
    private static final String PRINT_PREF = "v7_print_state";
    private static final String KEY_STATE = "state"; // idle | pending | confirmed | unknown
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ExtPrinterService printer;
    private boolean serviceBound;
    private TextView status;
    private Button printButton;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            printer = ExtPrinterService.Stub.asInterface(binder);
            show("Servicio SUNMI K2 conectado. Verificando estado.");
            checkPrinter();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            printer = null;
            show("Servicio SUNMI desconectado. No se imprimirá.");
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 30, 32, 30);
        root.setBackgroundColor(Color.rgb(250, 244, 232));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("CARRUBO - V7 / PRUEBA K2");
        title.setTextSize(25);
        title.setTextColor(Color.rgb(118, 32, 25));
        title.setGravity(Gravity.CENTER);
        root.addView(title, full());

        TextView note = new TextView(this);
        note.setText("SOLO DIAGNÓSTICO · NO COBRA · NO EMITE BOLETAS · NO ENVÍA COMANDAS\n\nInstalada en paralelo a V6. Imprime únicamente un ticket fijo cuando el operador lo confirma.");
        note.setTextSize(16);
        note.setPadding(0, 30, 0, 24);
        root.addView(note, full());

        status = new TextView(this);
        status.setTextSize(18);
        status.setTextColor(Color.BLACK);
        status.setText("Buscando servicio SUNMI…");
        root.addView(status, full());

        Button check = new Button(this);
        check.setText("Verificar impresora");
        check.setOnClickListener(v -> checkPrinter());
        root.addView(check, full());

        printButton = new Button(this);
        printButton.setText("Imprimir ticket de prueba (sin cobro)");
        printButton.setOnClickListener(v -> confirmPrint());
        root.addView(printButton, full());

        Button reset = new Button(this);
        reset.setText("Reiniciar bloqueo manualmente");
        reset.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle("Revisar el papel antes de reiniciar")
            .setMessage("Si la impresión anterior quedó pendiente o desconocida, NO la repitas sin revisar el papel. Esta opción solo elimina el bloqueo local de diagnóstico.")
            .setNegativeButton("Volver", null)
            .setPositiveButton("Confirmar reinicio", (d, w) -> {
                prefs().edit().putString(KEY_STATE, "idle").apply();
                show("Bloqueo local reiniciado por operador.");
                updateButtons();
            }).show());
        root.addView(reset, full());
        setContentView(scroll);
        updateButtons();
        connect();
    }

    private LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(-1, -2);
    }
    private SharedPreferences prefs() { return getSharedPreferences(PRINT_PREF, MODE_PRIVATE); }
    private String state() { return prefs().getString(KEY_STATE, "idle"); }
    private void show(String msg) { ui.post(() -> { if (status != null) status.setText(msg + "\n\nIntento local: " + state()); }); }
    private void updateButtons() {
        boolean can = printer != null && !running.get() && "idle".equals(state());
        if (printButton != null) printButton.setEnabled(can);
    }
    private void connect() {
        Intent intent = new Intent("com.sunmi.extprinterservice.PrinterService");
        intent.setPackage("com.sunmi.extprinterservice");
        try {
            serviceBound = bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!serviceBound) show("Servicio com.sunmi.extprinterservice no disponible en este dispositivo.");
        } catch (Exception e) {
            show("No se pudo conectar al servicio SUNMI: " + e.getClass().getSimpleName());
        }
    }
    private void checkPrinter() {
        ExtPrinterService p = printer;
        if (p == null) { show("Impresora sin conexión."); updateButtons(); return; }
        io.execute(() -> {
            try {
                int s = p.getPrinterStatus();
                show("SUNMI estado " + s + (s == 0 ? " · NORMAL" : " · NO LISTA"));
            } catch (Exception e) { show("Error consultando impresora: " + e.getClass().getSimpleName()); }
            ui.post(this::updateButtons);
        });
    }
    private void confirmPrint() {
        if (printer == null || running.get() || !"idle".equals(state())) return;
        new AlertDialog.Builder(this)
            .setTitle("Impresión FÍSICA de diagnóstico")
            .setMessage("¿Imprimir exactamente un ticket fijo de prueba? No se cobrará, ni se conectará a Mercado Pago, ni se enviará a cocina.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Imprimir una vez", (d, w) -> beginPrint())
            .show();
    }
    private void beginPrint() {
        if (printer == null || !running.compareAndSet(false, true) || !"idle".equals(state())) { running.set(false); return; }
        // Reservar antes de ejecutar: ante una caída no se repite automáticamente.
        prefs().edit().putString(KEY_STATE, "pending").commit();
        ui.post(this::updateButtons);
        show("Intento reservado. Ejecutando en segundo plano.");
        final ExtPrinterService p = printer;
        ui.postDelayed(() -> {
            if (running.get() && "pending".equals(state())) {
                prefs().edit().putString(KEY_STATE, "unknown").commit();
                show("Resultado DESCONOCIDO. Revisar papel antes de intentar otra impresión.");
                updateButtons();
            }
        }, 25000);
        io.execute(() -> {
            String result;
            try {
                if (p.getPrinterStatus() != 0) throw new IllegalStateException("impresora no lista");
                p.startTransBuffer();
                p.printText("CARRUBO - PRUEBA K2 V7\nNO TRIBUTARIO\nSIN COBRO\nNO SE ENVIO A COCINA\n-------------------------\nTEST IMPRESORA - NO VENTA\n\n");
                p.cutPaper(1, 0);
                boolean ok = p.endTransBuffer(); // Única confirmación del servicio; no usar retorno de printText.
                if (ok) {
                    prefs().edit().putString(KEY_STATE, "confirmed").commit();
                    result = "Servicio SUNMI confirmó impresión. Verificar ticket físico.";
                } else {
                    prefs().edit().putString(KEY_STATE, "unknown").commit();
                    result = "No hubo confirmación de impresión. Revisar papel; sin reintentos automáticos.";
                }
            } catch (Exception e) {
                prefs().edit().putString(KEY_STATE, "unknown").commit();
                result = "Error o resultado ambiguo: " + e.getClass().getSimpleName() + ". Revisar papel.";
            } finally {
                running.set(false);
            }
            show(result);
            ui.post(this::updateButtons);
        });
    }
    @Override protected void onDestroy() {
        if (serviceBound) { try { unbindService(connection); } catch (Exception ignored) { } }
        printer = null;
        io.shutdown();
        super.onDestroy();
    }
}
