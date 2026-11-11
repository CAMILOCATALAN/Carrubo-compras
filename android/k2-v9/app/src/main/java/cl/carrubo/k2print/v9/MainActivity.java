package cl.carrubo.k2print.v9;

import android.app.Activity;
import java.security.MessageDigest;
import java.security.SecureRandom;
import android.content.Context;
import android.view.inputmethod.InputMethodManager;
import android.net.Uri;
import android.net.http.SslError;
import android.view.View;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.sunmi.externalprinterlibrary.api.ConnectCallback;
import com.sunmi.externalprinterlibrary.api.SunmiPrinter;
import com.sunmi.externalprinterlibrary.api.SunmiPrinterApi;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/** Demo-only, foreground-only K2 app: pair once, poll, print once; never charges. */
public final class MainActivity extends Activity {
  private WebView kioskWeb;
  private ScrollView adminPanel;
  private TextView kioskBanner;
  private boolean adminOpen=false;
  private static final String HOST="carrubo-pos-demo.lovable.app";
  private static final String BASE="/api/public/k2/sales/";
  private static final String PREF="carrubo_v9_sales";
  private static final String ST="status";
  private static final String LAST="job_id";
  private static final String ACK="ack_result";
  private static final String TOKEN="encrypted_token";
  private static final String IV="token_iv";
  private static final String ALIAS="carrubo_k2_v9_aes";
  private static final String CODE_PATTERN="^[A-F0-9]{24}$";
  private static final String TOKEN_PATTERN="^[a-f0-9]{64}$";
  private static final String UUID_PATTERN="^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";
  private final Handler ui=new Handler(Looper.getMainLooper());
  private final ExecutorService io=Executors.newSingleThreadExecutor();
  private final AtomicBoolean busy=new AtomicBoolean(false);
  private SunmiPrinterApi printer;
  private volatile boolean connected=false,ready=false,active=false,visible=false;
  private EditText codeInput, pinInput;
  private int failedAdminPins=0;
  private long adminPinLockedUntil=0L;
  private TextView label,detail;
  private Button listenButton,pairButton,reackButton,doneButton;
  private Runnable tick;
  private final ConnectCallback cb=new ConnectCallback(){
    @Override public void onFound(){message("Servicio SUNMI detectado.");}
    @Override public void onUnfound(){connected=false;ready=false;message("SUNMI desconectado.");controls();}
    @Override public void onConnect(){connected=true;checkPrinter();}
    @Override public void onDisconnect(){connected=false;ready=false;message("SUNMI desconectado.");controls();}
  };

  @Override public void onCreate(Bundle b){
    super.onCreate(b);
    ScrollView scroll=new ScrollView(this);
    LinearLayout box=new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(32,25,32,32);
    box.setBackgroundColor(Color.rgb(250,245,235));
    scroll.addView(box);
    TextView heading=new TextView(this);
    heading.setText("CARRUBO K2 - V9\nIMPRESION COMERCIAL");
    heading.setTextSize(23);heading.setGravity(Gravity.CENTER);
    heading.setTextColor(Color.rgb(132,36,26));box.addView(heading);
    TextView info=new TextView(this);
    info.setText("PILOTO COMERCIAL · COMANDAS SOLO DE PAGOS VERIFICADOS\nLa N950 entrega el voucher tributario; el K2 imprime la comanda para cocina.\n\nEmpareja con el codigo comercial del panel ADMIN. Mientras SALES_ENABLED esté OFF, no habrá pedidos reales.");
    info.setTextSize(16);info.setPadding(0,22,0,15);box.addView(info);
    codeInput=new EditText(this);codeInput.setSingleLine(true);codeInput.setTextSize(23);
    codeInput.setHint("Código de 24 caracteres");
    codeInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT|
      android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);box.addView(codeInput);
    pinInput=new EditText(this);
    pinInput.setSingleLine(true);pinInput.setTextSize(20);
    pinInput.setHint("Crea PIN de ajustes (6 digitos)");
    pinInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    box.addView(pinInput);
    pairButton=new Button(this);pairButton.setText("EMPAREJAR K2");
    pairButton.setOnClickListener(v->confirmPair());box.addView(pairButton);
    label=new TextView(this);label.setTextSize(16);label.setPadding(0,15,0,10);box.addView(label);
    Button check=new Button(this);check.setText("Verificar impresora");
    check.setOnClickListener(v->checkPrinter());box.addView(check);
    listenButton=new Button(this);listenButton.setText("INICIAR RECEPCION COMERCIAL");
    listenButton.setOnClickListener(v->toggle());box.addView(listenButton);
    reackButton=new Button(this);reackButton.setText("REENVIAR SOLO ACUSE (SIN IMPRIMIR)");
    reackButton.setOnClickListener(v->resendAck());box.addView(reackButton);
    doneButton=new Button(this);doneButton.setText("REVISE EL PAPEL / DESBLOQUEAR COLA");
    doneButton.setOnClickListener(v->confirmPaper());box.addView(doneButton);
    detail=new TextView(this);detail.setTextSize(17);detail.setTextColor(Color.BLACK);
    detail.setPadding(0,18,0,18);box.addView(detail);
    Button forget=new Button(this);forget.setText("OLVIDAR EMPAREJAMIENTO LOCAL");
    forget.setOnClickListener(v->new AlertDialog.Builder(this)
      .setTitle("Olvidar emparejamiento")
      .setMessage("Necesitarás generar un nuevo código en Lovable. ¿Confirmar?")
      .setNegativeButton("Cancelar",null)
      .setPositiveButton("Olvidar",(d,w)->{stop();eraseToken();message("Emparejamiento local olvidado.");controls();}).show());
    box.addView(forget);
    adminPanel=scroll;
    adminPanel.setVisibility(View.GONE);
    LinearLayout shell=new LinearLayout(this);
    shell.setOrientation(LinearLayout.VERTICAL);
    LinearLayout bar=new LinearLayout(this);
    bar.setOrientation(LinearLayout.HORIZONTAL);
    bar.setBackgroundColor(Color.rgb(35,35,38));
    kioskBanner=new TextView(this);
    kioskBanner.setText("CARRUBO V9 | PAGOS CONTROLADOS");
    kioskBanner.setTextColor(Color.WHITE);
    kioskBanner.setTextSize(16);
    kioskBanner.setGravity(Gravity.CENTER_VERTICAL);
    kioskBanner.setPadding(14,0,0,0);
    bar.addView(kioskBanner,new LinearLayout.LayoutParams(0,52,1));
    Button settings=new Button(this);
    settings.setText("AJUSTES");
    settings.setTextSize(12);
    settings.setOnClickListener(v->Toast.makeText(this,"Mantener presionado AJUSTES",Toast.LENGTH_SHORT).show());
    settings.setOnLongClickListener(v->{openAdminSecurely();return true;});
    bar.addView(settings,new LinearLayout.LayoutParams(144,52));
    shell.addView(bar,new LinearLayout.LayoutParams(-1,-2));
    FrameLayout viewHost=new FrameLayout(this);
    kioskWeb=new WebView(this);
    kioskWeb.setFocusableInTouchMode(true);
    viewHost.addView(kioskWeb,new FrameLayout.LayoutParams(-1,-1));
    adminPanel.setBackgroundColor(Color.rgb(250,245,235));
    viewHost.addView(adminPanel,new FrameLayout.LayoutParams(-1,-1));
    shell.addView(viewHost,new LinearLayout.LayoutParams(-1,0,1));
    setContentView(shell);
    configureWebView();
    kioskWeb.loadUrl("https://"+HOST+"/kiosk-v8");
    message("idle".equals(state())?"V9 abierta. Emparéjala una vez; la recepción se activará automáticamente si la cola está autorizada.":"Impresión anterior pendiente o ambigua; verificar papel antes de continuar.");
    controls();connect();
  }
  private String hashPin(String pin,String salt)throws Exception{
    byte[] hash=MessageDigest.getInstance("SHA-256").digest(("carrubo-v9:"+salt+":"+pin).getBytes(StandardCharsets.UTF_8));
    return Base64.encodeToString(hash,Base64.NO_WRAP);
  }
  private void saveAdminPin(String pin)throws Exception{
    byte[] salt=new byte[16];new SecureRandom().nextBytes(salt);
    String st=Base64.encodeToString(salt,Base64.NO_WRAP);
    if(!prefs().edit().putString("admin_pin_salt",st).putString("admin_pin_hash",hashPin(pin,st)).commit())
      throw new Exception("No se pudo guardar PIN local");
  }
  private void openAdminSecurely(){
    if(adminOpen){toggleAdmin();return;}
    if(readToken()==null){toggleAdmin();return;}
    long now=System.currentTimeMillis();
    if(now<adminPinLockedUntil){Toast.makeText(this,"Ajustes temporalmente bloqueados",Toast.LENGTH_LONG).show();return;}
    EditText input=new EditText(this);
    input.setHint("PIN de 6 digitos");
    input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    new AlertDialog.Builder(this).setTitle("Ajustes del K2")
      .setMessage("Ingresa el PIN local configurado al emparejar.")
      .setView(input)
      .setNegativeButton("Cancelar",null)
      .setPositiveButton("Entrar",(d,w)->{
        try {
          String salt=prefs().getString("admin_pin_salt",""),stored=prefs().getString("admin_pin_hash","");
          if(!stored.isEmpty() && MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8),
             hashPin(input.getText().toString(),salt).getBytes(StandardCharsets.UTF_8))){
            failedAdminPins=0;toggleAdmin();return;
          }
        }catch(Exception ignored){}
        failedAdminPins++;
        if(failedAdminPins>=5){adminPinLockedUntil=System.currentTimeMillis()+300000L;failedAdminPins=0;}
        Toast.makeText(this,"PIN incorrecto",Toast.LENGTH_LONG).show();
      }).show();
  }
  private void toggleAdmin(){
    adminOpen=!adminOpen;
    if(adminPanel!=null)adminPanel.setVisibility(adminOpen?View.VISIBLE:View.GONE);
    if(adminOpen){
      message("V9 COMERCIAL: configurar dispositivo y verificar impresora.");controls();
    } else {
      // Native EditText left the Android keyboard visible above the customer kiosk.
      if(codeInput!=null)codeInput.clearFocus();
      if(kioskWeb!=null)kioskWeb.requestFocus();
      try {
        InputMethodManager ime=(InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
        if(ime!=null && kioskWeb!=null)ime.hideSoftInputFromWindow(kioskWeb.getWindowToken(),0);
      }catch(Exception ignored){}
      getWindow().getDecorView().clearFocus();
      if(kioskWeb!=null)kioskWeb.requestFocus();
    }
  }
  private boolean allowedUrl(Uri url){
    return url!=null && "https".equalsIgnoreCase(url.getScheme())
      && HOST.equalsIgnoreCase(url.getHost()) && url.getPort()==-1;
  }
  private void configureWebView(){
    WebView.setWebContentsDebuggingEnabled(false);
    WebSettings s=kioskWeb.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setAllowFileAccess(false);
    s.setAllowContentAccess(false);
    s.setSupportMultipleWindows(false);
    s.setJavaScriptCanOpenWindowsAutomatically(false);
    s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    kioskWeb.setWebViewClient(new WebViewClient(){
      @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){
        return request.isForMainFrame()&&!allowedUrl(request.getUrl());
      }
      @Override public boolean shouldOverrideUrlLoading(WebView view,String url){
        try{return !allowedUrl(Uri.parse(url));}
        catch(Exception e){return true;}
      }
      @Override public void onReceivedSslError(WebView view,SslErrorHandler handler,SslError error){
        handler.cancel();
        if(kioskBanner!=null)kioskBanner.setText("ERROR HTTPS - DEMO BLOQUEADA");
      }
      @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){
        if(request.isForMainFrame()&&kioskBanner!=null)kioskBanner.setText("ERROR DE CARGA - REVISAR RED");
      }
      @Override public void onPageFinished(WebView view,String url){
        if(kioskBanner!=null&&allowedUrl(Uri.parse(url)))
          kioskBanner.setText("CARRUBO V9 | PAGOS CONTROLADOS");
      }
    });
    // Sin addJavascriptInterface: nunca permitir impresión o cobros desde JS.
  }
  @Override public void onBackPressed(){
    if(adminOpen){toggleAdmin();return;}
    if(kioskWeb!=null&&kioskWeb.canGoBack()){kioskWeb.goBack();return;}
    if(kioskWeb!=null){kioskWeb.loadUrl("https://"+HOST+"/kiosk-v8");return;}
    super.onBackPressed();
  }
  private SharedPreferences prefs(){return getSharedPreferences(PREF,MODE_PRIVATE);}
  private String state(){return prefs().getString(ST,"idle");}
  private String last(){return prefs().getString(LAST,"");}
  private void message(String s){ui.post(()->{if(detail!=null)detail.setText(s+
    "\n\nEstado: "+state()+(last().isEmpty()?"":"\nÚltimo job: "+last().substring(0,8)+"..."));});}
  private void controls(){ui.post(()->{
    boolean paired=readToken()!=null;
    if(label!=null)label.setText("Impresora: "+(ready?"lista":"no lista")+
      " / Emparejado: "+(paired?"SI":"NO")+" / Recepción: "+(active?"ON":"OFF"));
    if(pairButton!=null)pairButton.setEnabled(!busy.get()&&!active);
    if(listenButton!=null){listenButton.setText(active?"DETENER RECEPCION COMERCIAL":"INICIAR RECEPCION COMERCIAL");
      listenButton.setEnabled(active || (!busy.get()&&ready&&paired&&"idle".equals(state())));}
    if(reackButton!=null)reackButton.setEnabled(!busy.get()&&paired&&!last().isEmpty()&&
      ("sent_unverified".equals(state())||"unknown".equals(state())));
    if(doneButton!=null)doneButton.setEnabled(!busy.get()&&!active&&!last().isEmpty()&&
      ("sent_unverified".equals(state())||"unknown".equals(state())));
  });}
  private void connect(){
    try{printer=SunmiPrinterApi.getInstance();
      printer.setPrinter(SunmiPrinter.SunmiYKPriner,new String[0]);
      printer.connectPrinter(this,cb);
    }catch(Exception e){ready=false;message("SUNMI no disponible: "+e.getClass().getSimpleName());}
    controls();
  }
  private void checkPrinter(){
    if(!connected||printer==null){message("Impresora sin conexión.");controls();return;}
    io.execute(()->{
      try{int n=printer.getPrinterStatus();ready=(n==0);
        message(ready?"Impresora normal: lista.":"Impresora no lista, código "+n);
        if(ready&&readToken()!=null&&!active&&"idle".equals(state()))ui.post(()->{if(!active)toggle();});
      }catch(Exception e){ready=false;message("Error estado SUNMI: "+e.getClass().getSimpleName());}
      controls();
    });
  }
  private void confirmPair(){
    if(busy.get()||active)return;
    String code=codeInput.getText().toString().replaceAll("\\s+","").toUpperCase(Locale.ROOT);
    if(!code.matches(CODE_PATTERN)){message("Codigo comercial: exactamente 24 caracteres 0-9 o A-F.");return;}
    String pin=pinInput.getText().toString();
    if(!pin.matches("^[0-9]{6}$")){message("Debes crear un PIN local de 6 digitos para proteger Ajustes.");return;}
    new AlertDialog.Builder(this).setTitle("Emparejar K2")
      .setMessage("Asocia esta impresora a la cola comercial. El servidor solo enviara comandas de pagos verificados. No inicia cobros.")
      .setNegativeButton("Cancelar",null).setPositiveButton("Emparejar",(d,w)->pair(code,pin)).show();
  }
  private void pair(String code,String pin){
    if(!busy.compareAndSet(false,true))return;controls();message("Emparejando con Lovable...");
    io.execute(()->{
      try{
        JSONObject response=post("pair",new JSONObject().put("code",code),null);
        String token=response.optString("device_token",response.optString("token"));
        if(!token.matches(TOKEN_PATTERN))throw new Exception("Token comercial no valido");
        saveToken(token);
        saveAdminPin(pin);
        message("Dispositivo comercial emparejado. Recepcion solo cuando el servidor habilite cola comercial.");
        ui.post(()->{codeInput.setText("");pinInput.setText("");});
      }catch(Exception e){message("Emparejamiento falló: "+describe(e));}
      finally{busy.set(false);controls();}
    });
  }
  private void toggle(){
    if(active){stop();message("Recepción detenida.");controls();return;}
    if(readToken()==null||!ready||!"idle".equals(state())){message("Requiere emparejar, impresora lista y estado idle.");return;}
    active=true;controls();message("Recepcion comercial activa. Consultando la cola segura cada 7 segundos.");
    tick=new Runnable(){@Override public void run(){
      if(!active||!visible)return;
      poll();
      if(active)ui.postDelayed(this,7000);
    }};
    if(visible)ui.post(tick);
  }
  private void stop(){active=false;if(tick!=null)ui.removeCallbacks(tick);controls();}
  private void poll(){
    if(!busy.compareAndSet(false,true))return;
    String key=readToken();
    if(key==null){busy.set(false);stop();return;}
    io.execute(()->{
      try{
        JSONObject response=post("poll",new JSONObject(),key);
        JSONObject job=response.optJSONObject("job");
        if(job==null)return; // Nada que imprimir; no hacer reintentos de orden
        String id=job.optString("id"),ticket=job.optString("ticket");
        validTicket(id,ticket);
        if(!prefs().edit().putString(ST,"claimed").putString(LAST,id).remove(ACK).commit())
          throw new Exception("No se pudo reservar trabajo local");
        if(printer.getPrinterStatus()!=0)throw new Exception("Impresora no lista");
        if(!prefs().edit().putString(ST,"printing_unknown").commit())
          throw new Exception("No se guardo el estado antes de imprimir");
        message("Comanda de cocina por pago verificado: imprimiendo...");
        printer.printText(ticket+"\n\n\n\n\n\n\n\n\n\n");
        printer.cutPaper(1,0);
        if(!prefs().edit().putString(ST,"sent_unverified").putString(ACK,"sent_unverified").commit())
          throw new Exception("Sin registro local posterior a impresion");
        try{
          acknowledge(id,"sent_unverified",key);
          // SDK confirmo comandos y backend acepto ACK. La marca del servidor sigue
          // sent_unverified (no significa papel verificado); podemos procesar otra orden.
          prefs().edit().putString(ST,"idle").commit();
          message("Comanda entregada al SDK y acuse recibido; revision de papel por operador.");
        }catch(Exception e){
          stop();
          message("Se envio comanda a impresora pero fallo acuse; revisar papel, NO reimprimir. "+describe(e));
        }
      }catch(Exception e){
        if("claimed".equals(state())||"printing_unknown".equals(state())){
          prefs().edit().putString(ST,"unknown").putString(ACK,"unknown").commit();
          try{acknowledge(last(),"unknown",key);}catch(Exception ignored){}
          stop();
          message("RESULTADO DESCONOCIDO. Revisar papel; no reimprimir. "+describe(e));
        }else{
          message("Cola comercial sin trabajo disponible: "+describe(e));
          if(describe(e).contains("HTTP 401")||describe(e).contains("HTTP 403")||describe(e).contains("HTTP 503"))stop();
        }
      }finally{busy.set(false);controls();}
    });
  }
  private void validTicket(String id,String ticket)throws Exception{
    if(!id.matches(UUID_PATTERN)||id.equals(last())||!"idle".equals(state()))throw new Exception("Trabajo comercial duplicado o invalido");
    if(ticket.length()<30||ticket.length()>2000)throw new Exception("Longitud de comanda invalida");
    if(!ticket.contains("CARRUBO")||!ticket.contains("COMANDA DE COCINA")||!ticket.contains("NO TRIBUTARIO"))
      throw new Exception("No es una comanda comercial autorizada");
    for(String line:ticket.split("\n",-1)){
      if(line.length()>32||line.indexOf('\r')>=0||line.indexOf('\u001b')>=0||
         line.indexOf('\u0000')>=0)throw new Exception("Texto incompatible con papel de 58 mm");
      for(int i=0;i<line.length();i++)if(line.charAt(i)>127)throw new Exception("Comanda no ASCII");
    }
  }
  private void acknowledge(String job,String result,String key)throws Exception{
    if(!job.matches(UUID_PATTERN))throw new Exception("ID de comanda invalido");
    JSONObject resp=post("ack",new JSONObject().put("jobId",job).put("result",result),key);
    if(!result.equals(resp.optString("status")))throw new Exception("ACK no confirmado por backend");
    message("ACK comercial: "+result+"; requiere verificar papel cuando corresponda.");
  }
  private void resendAck(){
    if(!busy.compareAndSet(false,true))return;
    final String job=last(),key=readToken(),res=prefs().getString(ACK,"unknown");
    if(key==null||job.isEmpty()){busy.set(false);controls();return;}
    controls();io.execute(()->{
      try{acknowledge(job,res,key);}catch(Exception e){message("Error reenvío ACK: "+describe(e));}
      finally{busy.set(false);controls();}
    });
  }
  private void confirmPaper(){
    if(active||busy.get()||last().isEmpty())return;
    new AlertDialog.Builder(this).setTitle("Revisar papel")
      .setMessage("Confirma solo después de comprobar físicamente si se imprimió. NO reenvía ticket ni confirma en el servidor.")
      .setNegativeButton("Cancelar",null)
      .setPositiveButton("Verificado",(d,w)->{
        prefs().edit().putString(ST,"idle").commit();
        message("Comanda anterior revisada. Listo para cola comercial.");
        controls();
      }).show();
  }
  private JSONObject post(String route,JSONObject body,String token)throws Exception{
    if(!"pair".equals(route)&&!"poll".equals(route)&&!"ack".equals(route))throw new Exception("Ruta inválida");
    URL url=new URL("https",HOST,BASE+route);
    if(!"https".equals(url.getProtocol())||!HOST.equals(url.getHost()))throw new Exception("Origen inválido");
    HttpsURLConnection conn=(HttpsURLConnection)url.openConnection();
    try{
      conn.setConnectTimeout(7000);conn.setReadTimeout(13000);conn.setInstanceFollowRedirects(false);
      conn.setRequestMethod("POST");conn.setDoOutput(true);
      conn.setRequestProperty("Content-Type","application/json; charset=utf-8");
      conn.setRequestProperty("Accept","application/json");
      if(token!=null){if(!token.matches(TOKEN_PATTERN))throw new Exception("Token inválido");
        conn.setRequestProperty("Authorization","Bearer "+token);}
      byte[] b=body.toString().getBytes(StandardCharsets.UTF_8);
      if(b.length>256)throw new Exception("Cuerpo demasiado largo");
      try(OutputStream out=conn.getOutputStream()){out.write(b);}
      int code=conn.getResponseCode();
      if(code!=200)throw new Exception("HTTP "+code+" ("+route+")");
      ByteArrayOutputStream out=new ByteArrayOutputStream();
      try(InputStream in=conn.getInputStream()){
        byte[] tmp=new byte[512];int n;
        while((n=in.read(tmp))!=-1){out.write(tmp,0,n);if(out.size()>3500)throw new Exception("Respuesta demasiado grande");}
      }
      return new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
    }finally{conn.disconnect();}
  }
  private String describe(Exception e){
    String x=e.getMessage();return e.getClass().getSimpleName()+(x==null?"":(": "+x.substring(0,Math.min(x.length(),70))));
  }
  private SecretKey secret()throws Exception{
    KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
    if(ks.containsAlias(ALIAS))return (SecretKey)ks.getKey(ALIAS,null);
    KeyGenerator kg=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
    kg.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
      .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
      .setRandomizedEncryptionRequired(true).build());
    return kg.generateKey();
  }
  private void saveToken(String token)throws Exception{
    Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,secret());
    String encrypted=Base64.encodeToString(c.doFinal(token.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
    String iv=Base64.encodeToString(c.getIV(),Base64.NO_WRAP);
    if(!prefs().edit().putString(TOKEN,encrypted).putString(IV,iv).commit())
      throw new Exception("No fue posible guardar token cifrado");
  }
  private String readToken(){
    try{
      String data=prefs().getString(TOKEN,""),iv=prefs().getString(IV,"");
      if(data.isEmpty()||iv.isEmpty())return null;
      Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.DECRYPT_MODE,secret(),new GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)));
      String key=new String(c.doFinal(Base64.decode(data,Base64.NO_WRAP)),StandardCharsets.UTF_8);
      return key.matches(TOKEN_PATTERN)?key:null;
    }catch(Exception e){return null;}
  }
  private void eraseToken(){
    prefs().edit().remove(TOKEN).remove(IV).remove(ST).remove(LAST).remove(ACK).remove("admin_pin_hash").remove("admin_pin_salt").commit();
  }
  @Override protected void onResume(){super.onResume();visible=true;if(active&&tick!=null)ui.post(tick);}
  @Override protected void onPause(){visible=false;if(tick!=null)ui.removeCallbacks(tick);super.onPause();}
  @Override protected void onDestroy(){
    visible=false;stop();
    try{if(printer!=null)printer.disconnectPrinter(this);}catch(Exception ignored){}
    if(kioskWeb!=null){kioskWeb.stopLoading();kioskWeb.destroy();kioskWeb=null;}
    io.shutdown();super.onDestroy();
  }
}
