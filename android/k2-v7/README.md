Carrubo K2 V7.4 DEMO Automatica — solo diagnóstico.
Paquete Android cl.carrubo.k2print.v74 diferente de V6/V7.3.
Prueba de impresora SUNMI K2: printText y cutPaper, controlador verificado físicamente.
NO cobra, NO imprime boleta tributaria, NO envía cocina. Funciona únicamente en primer plano.
Backend público requiere nueva autorización de publicación (aún no publicado).
URL fija: https://carrubo-pos-demo.lovable.app/api/public/k2/v74-demo/
1) Publicar backend DEMO con consentimiento y verificar acceso GET 405; 2) panel /admin/impresion-automatica: crear emparejamiento de 24 hex (caduca en 5 minutos); 3) K2: introducir código una vez, se guarda token cifrado por Android Keystore; 4) Iniciar recepción DEMO; 5) admin encola 1 Margherita + 1 lata de $11.890 provisionales; 6) K2 reclama una sola vez, imprime y ACK sent_unverified. Operador valida papel, panel admin confirma salida.
Todos los tickets son ASCII de 32 columnas con 10 líneas al final. Ante dudas o reinicios, la APK no reimprime: se necesita reconciliación manual. ACK no certifica papel.
El prototipo es APK DEBUG, no certificado para pagos ni impresión comercial. Mantener V6 sin cambios.