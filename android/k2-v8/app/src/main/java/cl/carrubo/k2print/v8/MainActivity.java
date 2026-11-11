package cl.carrubo.k2print.v8;

import android.app.Activity;
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
  private static final String BASE="/api/public/k2/v74-demo/";
  private static final String PREF="carrubo_v74_demo";
  private static final String ST="status";
  private static final String LAST="job_id";
  private static final String ACK="ack_result";
  private static final String TOKEN="encrypted_token";
  private static final String IV="token_iv";
  private static final String ALIAS="carrubo_k2_v74_aes";
  private static final String CODE_PATTERN="^[A-F0-9]{24}$";
  private static final String TOKEN_PATTERN="^[A-Za-z0-9_-]{43}$";
  private static final String UUID_PATTERN="^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";
  private final Handler ui=new Handler(Looper.getMainLooper());
  private final ExecutorService io=Executors.newSingleThreadExecutor();
  private final AtomicBoolean busy=new AtomicBoolean(false);
  private SunmiPrinterApi printer;
  private volatile boolean connected=false,ready=false,active=false,visible=false;
  private EditText codeInput;
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
    heading.setText("CARRUBO K2 - V8\nIMPRESION NATIVA DEMO");
    heading.setTextSize(23);heading.setGravity(Gravity.CENTER);
    heading.setTextColor(Color.rgb(132,36,26));box.addView(heading);
    TextView info=new TextView(this);
    info.setText("V8 PILOTO, NO COBRA, NO TRIBUTARIO\nNO ENVIA A COCINA. V6 INTACTA.\n\nLa V8 muestra el catálogo Carrubo y a la vez recibe comandas DEMO autorizadas por el administrador.");
    info.setTextSize(16);info.setPadding(0,22,0,15);box.addView(info);
    codeInput=new EditText(this);codeInput.setSingleLine(true);codeInput.setTextSize(23);
    codeInput.setHint("Código de 24 caracteres");
    codeInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT|
      android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);box.addView(codeInput);
    pairButton=new Button(this);pairButton.setText("EMPAREJAR K2");
    pairButton.setOnClickListener(v->confirmPair());box.addView(pairButton);
    label=new TextView(this);label.setTextSize(16);label.setPadding(0,15,0,10);box.addView(label);
    Button check=new Button(this);check.setText("Verificar impresora");
    check.setOnClickListener(v->checkPrinter());box.addView(check);
    listenButton=new Button(this);listenButton.setText("INICIAR RECEPCION AUTOMATICA DEMO");
    listenButton.setOnClickListener(v->toggle());box.addView(listenButton);
    reackButton=new Button(this);reackButton.setText("REENVIAR SOLO ACUSE (SIN IMPRIMIR)");
    reackButton.setOnClickListener(v->resendAck());box.addView(reackButton);
    doneButton=new Button(this);doneButton.setText("REVISE EL PAPEL / PERMITIR SIGUIENTE DEMO");
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
    kioskBanner.setText("CARRUBO V8 | DEMO SIN COBRO");
    kioskBanner.setTextColor(Color.WHITE);
    kioskBanner.setTextSize(16);
    kioskBanner.setGravity(Gravity.CENTER_VERTICAL);
    kioskBanner.setPadding(14,0,0,0);
    bar.addView(kioskBanner,new LinearLayout.LayoutParams(0,52,1));
    Button settings=new Button(this);
    settings.setText("AJUSTES");
    settings.setTextSize(12);
    settings.setOnClickListener(v->Toast.makeText(this,"Mantener presionado AJUSTES",Toast.LENGTH_SHORT).show());
    settings.setOnLongClickListener(v->{toggleAdmin();return true;});
    bar.addView(settings,new LinearLayout.LayoutParams(144,52));
    shell.addView(bar,new LinearLayout.LayoutParams(-1,-2));
    FrameLayout viewHost=new FrameLayout(this);
    kioskWeb=new WebView(this);
    viewHost.addView(kioskWeb,new FrameLayout.LayoutParams(-1,-1));
    adminPanel.setBackgroundColor(Color.rgb(250,245,235));
    viewHost.addView(adminPanel,new FrameLayout.LayoutParams(-1,-1));
    shell.addView(viewHost,new LinearLayout.LayoutParams(-1,0,1));
    setContentView(shell);
    configureWebView();
    kioskWeb.loadUrl("https://"+HOST+"/");
    message("idle".equals(state())?"K2 iniciado. Empareja o activa recepción.":"Resultado anterior pendiente o ambiguo. Revisa el papel.");
    controls();connect();
  }
  private void toggleAdmin(){
    adminOpen=!adminOpen;
    if(adminPanel!=null)adminPanel.setVisibility(adminOpen?View.VISIBLE:View.GONE);
    if(adminOpen){message("V8 DEMO: emparejar y activar recepción.");controls();}
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
          kioskBanner.setText("CARRUBO V8 | DEMO SIN COBRO");
      }
    });
    // Sin addJavascriptInterface: nunca permitir impresión o cobros desde JS.
  }
  @Override public void onBackPressed(){
    if(adminOpen){toggleAdmin();return;}
    if(kioskWeb!=null&&kioskWeb.canGoBack()){kioskWeb.goBack();return;}
    if(kioskWeb!=null){kioskWeb.loadUrl("https://"+HOST+"/");return;}
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
    if(listenButton!=null){listenButton.setText(active?"DETENER RECEPCION DEMO":"INICIAR RECEPCION AUTOMATICA DEMO");
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
      }catch(Exception e){ready=false;message("Error estado SUNMI: "+e.getClass().getSimpleName());}
      controls();
    });
  }
  private void confirmPair(){
    if(busy.get()||active)return;
    String code=codeInput.getText().toString().replaceAll("\\s+","").toUpperCase(Locale.ROOT);
    if(!code.matches(CODE_PATTERN)){message("Código: exactamente 24 caracteres 0-9 o A-F.");return;}
    new AlertDialog.Builder(this).setTitle("Emparejar K2")
      .setMessage("Esto permite recibir únicamente comandas DEMO sin cobros.")
      .setNegativeButton("Cancelar",null).setPositiveButton("Emparejar",(d,w)->pair(code)).show();
  }
  private void pair(String code){
    if(!busy.compareAndSet(false,true))return;controls();message("Emparejando con Lovable...");
    io.execute(()->{
      try{
        JSONObject response=post("pair",new JSONObject().put("code",code),null);
        if(!"CARRUBO_V74_PAIRED".equals(response.optString("kind"))||response.optInt("version")!=1)
          throw new Exception("Respuesta de pairing inválida");
        String token=response.optString("device_token");
        if(!token.matches(TOKEN_PATTERN))throw new Exception("Token no válido");
        saveToken(token);message("Emparejado. Activar recepción DEMO.");
        ui.post(()->codeInput.setText(""));
      }catch(Exception e){message("Emparejamiento falló: "+describe(e));}
      finally{busy.set(false);controls();}
    });
  }
  private void toggle(){
    if(active){stop();message("Recepción detenida.");controls();return;}
    if(readToken()==null||!ready||!"idle".equals(state())){message("Requiere emparejar, impresora lista y estado idle.");return;}
    active=true;controls();message("Recepción DEMO activa. Consultando cada 7 segundos.");
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
        if(!"CARRUBO_V74_DEMO".equals(response.optString("kind"))||response.optInt("version")!=1)
          throw new Exception("Poll inesperado");
        if(response.isNull("job")&&!response.has("job_id"))return;
        String id=response.optString("job_id"),ticket=response.optString("text");
        validTicket(id,ticket);
        stop();
        if(!prefs().edit().putString(ST,"claimed").putString(LAST,id).remove(ACK).commit())
          throw new Exception("No se pudo reservar en disco");
        if(printer.getPrinterStatus()!=0)throw new Exception("Impresora no lista");
        if(!prefs().edit().putString(ST,"printing_unknown").commit())
          throw new Exception("No se pudo registrar antes de imprimir");
        message("Comanda DEMO recibida: imprimiendo...");
        printer.printText(ticket);
        printer.cutPaper(1,0);
        prefs().edit().putString(ST,"sent_unverified").putString(ACK,"sent_unverified").commit();
        message("Enviado a SUNMI. Revisar papel. No se repetirá automáticamente.");
        try{acknowledge(id,"sent_unverified",key);}
        catch(Exception e){message("Papel a revisar. Falta ACK al servidor: "+describe(e)+". Puede reenviar SOLO ACK.");}
      }catch(Exception e){
        if("claimed".equals(state())||"printing_unknown".equals(state())){
          prefs().edit().putString(ST,"unknown").putString(ACK,"unknown").commit();
          try{acknowledge(last(),"unknown",key);}catch(Exception ignored){}
          message("RESULTADO DESCONOCIDO. Revisar papel: "+describe(e));
        }else{
          message("Consulta sin trabajo: "+describe(e));
          if(describe(e).contains("HTTP 401")){stop();eraseToken();}
        }
      }finally{busy.set(false);controls();}
    });
  }
  private void validTicket(String id,String ticket)throws Exception{
    if(!id.matches(UUID_PATTERN)||id.equals(last())||!"idle".equals(state()))throw new Exception("Job duplicado o inválido");
    if(ticket.length()<30||ticket.length()>1700)throw new Exception("Ticket demasiado corto/largo");
    if(!ticket.contains("CARRUBO")||!ticket.contains("NO TRIBUTARIO")||
      !ticket.contains("NO SE COBRO")||!ticket.contains("NO SE ENVIO A COCINA"))
      throw new Exception("Faltan marcadores DEMO");
    for(String line:ticket.split("\n",-1)){
      if(line.length()>32||line.indexOf('\r')>=0||line.indexOf('\u001b')>=0||
        line.indexOf('\u0000')>=0)throw new Exception("Formato no válido para 58mm");
      for(int i=0;i<line.length();i++)if(line.charAt(i)>127)throw new Exception("Texto no ASCII");
    }
  }
  private void acknowledge(String job,String result,String key)throws Exception{
    if(!job.matches(UUID_PATTERN))throw new Exception("Job no válido");
    JSONObject resp=post("ack",new JSONObject().put("job_id",job).put("result",result),key);
    if(!resp.optBoolean("ok",false))throw new Exception("ACK no aceptado");
    message("ACK recibido por servidor: "+result+". Verificar papel.");
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
        message("Listo para otra comanda DEMO autorizada. Activa recepción.");
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
    prefs().edit().remove(TOKEN).remove(IV).remove(ST).remove(LAST).remove(ACK).commit();
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
