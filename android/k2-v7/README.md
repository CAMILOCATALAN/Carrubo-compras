# Carrubo K2 V7.3 — Comanda demo desde Lovable

APK Android de DIAGNÓSTICO únicamente. Package `cl.carrubo.k2print.v73` distinto de V6/V7.2. Usa la misma secuencia SUNMI K2 que ya imprimió: `SunmiPrinterApi.printText` seguido de `cutPaper(1,0)`.

## Flujo protegido (no pagos)
1. En el panel admin del proyecto Lovable, generar un código 16 caracteres hex de un solo uso. Expires 5 min.
2. En el K2 abrir V7.3, verificar impresora, introducir código, pulsar 'RECIBIR E IMPRIMIR COMANDA DEMO'.
3. APK hace POST HTTPS SOLO a `https://id-preview--66ed1f1d-568e-4eac-814b-9dc03afb9bfc.lovable.app/api/public/k2/v7-demo/claim`. No consulta Mercado Pago.
4. Validar JSON `{kind:"CARRUBO_V7_DEMO",version:1,job_id:uuid,text:...}`, marcadores NO TRIBUTARIO, NO SE COBRO, NO SE ENVIO A COCINA. Recibe el texto generado por servidor, nunca el del usuario.
5. App guarda estado de intento ANTES de envío y no hace retry automático tras errores/resultado ambiguo. Código se consume atómicamente en servidor.
6. Verificar papel real, y reiniciar manualmente solo para un código NUEVO.

## Seguridad
- APK debug NO es versión comercial ni firmada para release. No contiene llaves ni tokens de Mercado Pago, y no puede cobrar.
- No imprime ticket tributario ni genera pedido real de cocina.
- El host de vista previa está anclado a una URL exacta. Solo HTTPS, redirecciones deshabilitadas. No usa WebView ni JavaScript.
- El backend debe estar publicado en preview y habilitar endpoint SOLO para código DEMO. Si no existe, la app informará HTTP 404 y NO imprimirá.
- El indicador 'sent_unverified' significa que el SDK aceptó el comando; no prueba por sí solo que el papel salió. Confirmar físicamente.
- V6 y V7.2 intactas, paquetización separada.
