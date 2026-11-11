# Carrubo K2 V7.2 — diagnóstico de salida de papel

Esta versión de PRUEBA, separada de V6 y V7.1, se compila como `cl.carrubo.k2print.v72`.

**Resultado V7.1:** la impresora imprimió un ticket, pero quedó poco accesible en la ranura. Los cobros no estaban habilitados.
**Cambio V7.2:** exactamente el mismo SDK SUNMI K2 / `SunmiPrinterApi.printText` y `cutPaper(1, 0)`, sin modo transaccional, con **10 saltos de línea adicionales** después del mensaje fijo para que avance el papel antes del corte parcial. No añade API de cobro ni WebView.

Para probar:
1. Verificar y retirar el papel de la prueba V7.1. No tirar con fuerza si el papel está trabado.
2. Instalar APK `Carrubo_K2_V7_2_Prueba_Papel.apk`. Mantener V6 sin cambios.
3. Abrir "Carrubo K2 — V7.2 DIAGNOSTICO"; verificar impresora normal/lista.
4. Pulsar "PRUEBA V7.2: AVANCE DE PAPEL + CORTE" y confirmar UNA VEZ.
5. Revisar si el ticket sale lo bastante para sujetarlo y si el corte parcial funciona. Enviar foto.
6. El estado pasa a unknown porque el SDK solo certifica recepción de comandos, no que el papel haya salido. No repetir automáticamente: revisar papel antes de usar botón de restablecer.

APK de DEBUG firmada solo para pruebas internas; NO es sistema de ventas. El nombre de paquete es distinto para poder instalar junto a V6 y V7.1. La emisión de boleta SII, pago real, integración de cocina y confirmación física siguen pendientes.
