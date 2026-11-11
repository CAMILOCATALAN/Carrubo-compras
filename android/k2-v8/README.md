# Carrubo SUNMI K2 V8 Unificada DEMO
Aplicación Android en paquete cl.carrubo.k2print.v8 independiente de V6 y V7.4.
Incluye WebView para el catálogo actual de Carrubo y el cliente SUNMI K2 de recepción automática DEMO dentro de la misma aplicación.
CATÁLOGO ACTUAL: MODO DEMO, NO COBRA. Un pedido DEMO finalizado en la pantalla no se convierte todavía en orden de Mercado Pago ni dispara impresión comercial.
Impresión: solo jobs fijos CARRUBO_V74_DEMO creados desde panel admin /admin/impresion-automatica. Servidor nunca enviará órdenes comerciales a esta APK.
Se reutiliza SUNMI printText y cutPaper(1,0) que ya imprimieron físicamente. El SDK recibe ticket solo con marcadores NO TRIBUTARIO, NO SE COBRO y NO SE ENVIO A COCINA.
Mantén pulsado AJUSTES para ver la pantalla nativa. Genera código nuevo de 24 caracteres en el admin, ingrésalo en V8, empareja, verifica impresora e inicia recepción DEMO. Vuelve al catálogo manteniendo AJUSTES; la recepción sigue activa mientras app esté en primer plano.
Al emparejar V8 se revoca el emparejamiento anterior de V7.4. Conservar V6 instalada y funcional.
No existe puente JavaScript ni llamadas a Mercado Pago en Android. WebView solo permite navegaciones HTTPS al dominio Carrubo y falla ante SSL inválido. Token dispositivo está cifrado con Android Keystore.
Ante error o desconexión un trabajo reclamado no se vuelve a imprimir automáticamente. Revisar papel antes de dar siguiente job.
APK compilada con firma DEBUG para pruebas; NO válida como versión de ventas reales. Falta interfaz de pedido comercial, N950 con token dedicado, estado verified GET, voucher, cola comercial separada y APK release auditada.
Si el WebView del K2 es demasiado antiguo para renderizar el catálogo, actualizar Android System WebView del tótem antes de considerar otros cambios.