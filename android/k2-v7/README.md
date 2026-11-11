# Carrubo K2 V7 — APK de diagnóstico nativo

Este proyecto se construye en GitHub Actions sobre la rama aislada build/k2-v7-diagnostico, SIN modificar la rama main ni APK V6.

- App ID: cl.carrubo.k2print.v7, permite instalar paralela a V6 cl.carrubo.k2print.v6.
- Objetivo: PRIMER TICKET FÍSICO DE PRUEBA, sin pagos/órdenes/comandas ni WebView. No es aún el POS V7 productivo.
- Librería oficial com.sunmi:external-printerlibrary:1.0.12 (Apache 2.0), servicio K2 com.sunmi.extprinterservice.
- La impresión solo se produce tras confirmación explícita del operador.
- Verifica estado 0 (normal); comienza transacción, envía texto de prueba, corta papel y confirma con endTransBuffer(). NO usa retorno de printText como prueba de impresión.
- Ejecuta transacción en hilo independiente; bloquea reintentos si queda pendiente/desconocido.
- Estado local persistido; solo reinicio manual tras verificar el papel.
- No solicita permiso de internet, no usa datos de tarjetas, no envía requests a Mercado Pago.
- Android minSdk 25 (K2 Android 7.1.2); targetSdk 28.
- APK de Actions es DEBUG firmado con una clave de prueba; NO instalar para manejar dinero ni dejar en producción. Para producción crear release propia firmada y auditar software.
- Descarga el artifact de Actions, extrae el APK, pásalo al K2, instala con APK junto a V6.
- Si el servicio externo no existe, el botón de impresión permanece deshabilitado. Confirmar versión firmware K2 y que SDK coincide.
- Tras imprimir el ticket fijo, no reemplazar V6: se necesitará un canal autenticado de trabajos de impresión tras pagos confirmados, boleta SII y bloqueo de duplicados persistente en servidor.

Estado: código preparado para compilación/validación; compatibilidad real requiere prueba en el equipo.
