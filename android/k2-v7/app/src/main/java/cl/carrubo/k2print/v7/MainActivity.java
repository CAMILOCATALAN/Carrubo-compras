package cl.carrubo.k2print.v7;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.sunmi.externalprinterlibrary.api.ConnectCallback;
import com.sunmi.externalprinterlibrary.api.SunmiPrinter;
import com.sunmi.externalprinterlibrary.api.SunmiPrinterApi;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * V7.3 diagnostic: server creates a fixed non-tax DEMO ticket and one-time code.
 * The operator manually enters that code in this APK, then the APK claims it via HTTPS
 * and prints once with the K2 printer SDK that physically worked in V7.2.
 * NO Mercado Pago calls, NO client orders, NO background polling, NO WebView.
 */
public final class MainActivity extends Activity {
  private static final String HOST =
      "id-preview--66ed1f1d-568e-4eac-814b-9dc03afb9bfc.lovable.app";
  private static final String PATH = "/api/public/k2/v7-demo/claim";
  private static final String PREFS = "carrubo_k2_v73";
  private static final String ST = "state";
  private static final String LAST = "last_job_id";
  private static final String CODE_RE = "^[A-F0-9]{16}$";
  private static final String UUID_RE =
      "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$";

  private final Handler ui = new Handler(Looper.getMainLooper());
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final AtomicBoolean busy = new AtomicBoolean(false);
  private volatile boolean connected = false;
  private volatile boolean printerReady = false;
  private SunmiPrinterApi api;
  private TextView status;
  private Button printButton;
  private EditText codeField;

  private final ConnectCallback callback = new ConnectCallback() {
    @Override public void onFound() { setStatus("SUNMI detectado"); }
    @Override public void onUnfound() {
      connected = false; printerReady = false;
      setStatus("SUNMI no detectado"); updateButton();
    }
    @Override public void onConnect() {
      connected = true; setStatus("Servicio SUNMI conectado"); checkPrinter();
    }
    @Override public void onDisconnect() {
      connected = false; printerReady = false;
      setStatus("Servicio SUNMI desconectado"); updateButton();
    }
  };

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    ScrollView scroll = new ScrollView(this);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(35, 28, 35, 30);
    root.setBackgroundColor(Color.rgb(250, 245, 236));
    scroll.addView(root);

    TextView header = new TextView(this);
    header.setText("CARRUBO K2 - V7.3\nCOMANDA DESDE SERVIDOR");
    header.setTextSize(23); header.setTextColor(Color.rgb(132, 37, 27));
    header.setGravity(Gravity.CENTER);
    root.addView(header);
    TextView detail = new TextView(this);
    detail.setText("DEMOSTRACION - NO COBRA - NO TRIBUTARIO\n" +
      "V6 sigue instalada y sin cambios.\n" +
      "Obtén el código en el panel administrador de Lovable.\n" +
      "Solo imprime UNA comanda de prueba generada por el servidor.");
    detail.setPadding(0, 20, 0, 12); detail.setTextSize(17);
    root.addView(detail);

    codeField = new EditText(this);
    codeField.setSingleLine(true);
    codeField.setTextSize(25);
    codeField.setAllCaps(true);
    codeField.setHint("Código de 16 caracteres");
    codeField.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
      android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
    root.addView(codeField);

    status = new TextView(this);
    status.setTextSize(17); status.setTextColor(Color.BLACK);
    status.setPadding(0, 16, 0, 16);
    root.addView(status);

    Button check = new Button(this);
    check.setText("Verificar impresora");
    check.setOnClickListener(v -> checkPrinter());
    root.addView(check);

    printButton = new Button(this);
    printButton.setText("RECIBIR E IMPRIMIR COMANDA DEMO");
    printButton.setOnClickListener(v -> confirmClaim());
    root.addView(printButton);

    Button reset = new Button(this);
    reset.setText("Restablecer bloqueo DESPUÉS de revisar el papel");
    reset.setOnClickListener(v -> {
      if (busy.get()) return;
      new AlertDialog.Builder(this)
        .setTitle("Revisar el papel antes")
        .setMessage("Un código ya canjeado NO se puede reutilizar. Si quedó en estado desconocido, verifica si salió papel antes de restablecer. Esto solo permite ingresar un código NUEVO.")
        .setNegativeButton("Cancelar", null)
        .setPositiveButton("Ya revisé el papel", (d,w) -> {
          if (!busy.get()) {
            prefs().edit().putString(ST,"idle").commit();
            setStatus("Bloqueo restablecido. Solicita un CÓDIGO NUEVO.");
            updateButton();
          }
        }).show();
    });
    root.addView(reset);
    setContentView(scroll);

    if (!"idle".equals(state())) {
      setStatus("Existe una operación pendiente/ambigua de una sesión anterior. Verificar papel antes de restablecer.");
    } else {
      setStatus("Sin impresión pendiente. Conectando SUNMI.");
    }
    updateButton();
    connect();
  }

  private SharedPreferences prefs() { return getSharedPreferences(PREFS,MODE_PRIVATE); }
  private String state() { return prefs().getString(ST,"idle"); }
  private void setStatus(String msg) {
    ui.post(() -> {
      if (status != null) {
        String j = prefs().getString(LAST,"");
        status.setText(msg + "\n\nEstado persistente: " + state() +
          (j.isEmpty() ? "" : "\nÚltimo job: " + j.substring(0,Math.min(j.length(),8)) + "…"));
      }
    });
  }
  private void updateButton() {
    ui.post(() -> {
      if (printButton != null) printButton.setEnabled(connected && printerReady &&
        !busy.get() && "idle".equals(state()));
    });
  }
  private void connect() {
    try {
      api = SunmiPrinterApi.getInstance();
      api.setPrinter(SunmiPrinter.SunmiYKPriner,new String[0]);
      api.connectPrinter(this,callback);
    } catch (Exception e) {
      connected=false;printerReady=false;
      setStatus("SUNMI no disponible: "+e.getClass().getSimpleName());
    }
    updateButton();
  }
  private void checkPrinter() {
    if (!connected || api==null) {
      setStatus("Impresora SUNMI sin conexión"); updateButton(); return;
    }
    executor.execute(() -> {
      try {
        int state=api.getPrinterStatus();
        printerReady=(state==0);
        setStatus(printerReady ? "Impresora normal: lista" : "Impresora NO lista (código "+state+")");
      } catch(Exception e){
        printerReady=false;setStatus("Error verificando SUNMI: "+e.getClass().getSimpleName());
      }
      updateButton();
    });
  }

  private void confirmClaim() {
    if (!connected || !printerReady || busy.get() || !"idle".equals(state())) return;
    String code = codeField.getText().toString().replaceAll("\\s+","").toUpperCase(Locale.ROOT);
    if (!code.matches(CODE_RE)) {
      setStatus("Código inválido: deben ser exactamente 16 caracteres 0-9 o A-F.");
      return;
    }
    new AlertDialog.Builder(this)
      .setTitle("Imprimir comanda SOLO DEMO")
      .setMessage("Esta acción canjea un código de un solo uso del servidor y puede imprimir una única comanda NO TRIBUTARIA. No cobra dinero ni envía pedidos a cocina.")
      .setNegativeButton("Cancelar",null)
      .setPositiveButton("Recibir e imprimir DEMO",(d,w)-> runClaim(code))
      .show();
  }

  private void runClaim(String code) {
    if (!busy.compareAndSet(false,true) || !"idle".equals(state())) return;
    if (!prefs().edit().putString(ST,"requesting").commit()) {
      busy.set(false); setStatus("No se pudo registrar el intento local."); return;
    }
    updateButton();
    setStatus("Canjeando código contra servidor…");
    executor.execute(() -> {
      try {
        if (api==null || api.getPrinterStatus()!=0) throw new Exception("Impresora no lista");
        JSONObject job = claimOneTime(code);
        String id=job.getString("job_id"), ticket=job.getString("text");
        if (!prefs().edit().putString(LAST,id).putString(ST,"claimed").commit()) {
          throw new Exception("No se pudo persistir el job");
        }
        if (api.getPrinterStatus()!=0) throw new Exception("Impresora dejó de estar lista");
        prefs().edit().putString(ST,"printing_unknown").commit(); // crash-safe before native call
        setStatus("Código reclamado. Enviando texto y corte a SUNMI.");
        api.printText(ticket);
        api.cutPaper(1,0);
        prefs().edit().putString(ST,"sent_unverified").commit();
        setStatus("COMANDA DEMO enviada al SDK. Revisa el papel. No se repetirá automáticamente.");
      } catch(Exception e) {
        if ("requesting".equals(state()) || "claimed".equals(state())) {
          prefs().edit().putString(ST,"unknown").commit();
        }
        setStatus("Sin confirmación: " + e.getClass().getSimpleName() + " (" +
          (e.getMessage()==null ? "error" : e.getMessage()) + "). No repetir automáticamente; revisar papel.");
      } finally {
        busy.set(false);
        updateButton();
      }
    });
  }

  private JSONObject claimOneTime(String code) throws Exception {
    URL url=new URL("https",HOST,PATH);
    if (!"https".equals(url.getProtocol()) || !HOST.equals(url.getHost())) {
      throw new Exception("Destino HTTPS inválido");
    }
    HttpsURLConnection conn=(HttpsURLConnection)url.openConnection();
    try {
      conn.setConnectTimeout(8000);conn.setReadTimeout(12000);
      conn.setInstanceFollowRedirects(false);
      conn.setRequestMethod("POST");
      conn.setDoOutput(true);
      conn.setRequestProperty("Content-Type","application/json; charset=UTF-8");
      conn.setRequestProperty("Accept","application/json");
      byte[] body=new JSONObject().put("code",code).toString().getBytes(StandardCharsets.UTF_8);
      try(OutputStream out=conn.getOutputStream()){out.write(body);}
      int status=conn.getResponseCode();
      if(status!=200) throw new Exception("Servidor HTTP "+status+"; el código pudo vencer o ya fue utilizado");
      ByteArrayOutputStream bytes=new ByteArrayOutputStream();
      try(InputStream in=conn.getInputStream()){
        byte[] buf=new byte[512];int n;
        while((n=in.read(buf))!=-1){
          bytes.write(buf,0,n);
          if(bytes.size()>2600) throw new Exception("Respuesta demasiado larga");
        }
      }
      JSONObject data=new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
      if (!"CARRUBO_V7_DEMO".equals(data.optString("kind")) || data.optInt("version")!=1) {
        throw new Exception("Respuesta no es comanda DEMO autorizada");
      }
      String id=data.optString("job_id");
      String ticket=data.optString("text");
      if(!id.matches(UUID_RE) || ticket.length()<30 || ticket.length()>1700) {
        throw new Exception("Identificador o tamaño de ticket inválido");
      }
      if(!ticket.contains("CARRUBO") || !ticket.contains("NO TRIBUTARIO") ||
        !(ticket.contains("NO SE COBRO") || ticket.contains("NO SE COBRÓ")) ||
        !(ticket.contains("NO SE ENVIO A COCINA") || ticket.contains("NO SE ENVIÓ A COCINA"))) {
        throw new Exception("Faltan los avisos obligatorios de demostración");
      }
      for (String line:ticket.split("\n",-1)) {
        if (line.length()>48 || line.indexOf('\r')>=0 || line.indexOf('\u001b')>=0 ||
          line.indexOf('\u0000')>=0) {
          throw new Exception("Formato de ticket no admitido");
        }
      }
      if (id.equals(prefs().getString(LAST,""))) throw new Exception("Job repetido");
      return data;
    } finally {conn.disconnect();}
  }

  @Override protected void onDestroy() {
    try { if(api!=null)api.disconnectPrinter(this); } catch(Exception ignored){}
    executor.shutdown();
    super.onDestroy();
  }
}
