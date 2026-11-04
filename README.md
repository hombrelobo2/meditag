# MediTag — control de medicación con etiquetas NFC (NTAG215)

App Android (Kotlin + Jetpack Compose) que obliga a registrar cada toma acercando
al móvil la pegatina NFC pegada al medicamento. Solo se usa el **UID** de la
etiqueta, no hace falta grabar nada en ella.

## Funciones
- **Medicamentos**: nombre, pegatina NFC asociada (botón *Escanear*) y **rango horario** (desde / hasta).
- **Registrar toma**: acercar la pegatina con la app abierta *o cerrada* (Android abre la app al detectarla).
  Se acepta desde 30 min antes del inicio del rango (`EARLY_MARGIN_MIN` en `DoseManager.kt`).
- **Recordatorio** (notificación) al empezar el rango.
- **Alarma desagradable** al acabar el rango si no se ha tomado: onda cuadrada aguda
  (2,9/3,5 kHz) a volumen de alarma máximo, vibración continua y pantalla parpadeando en rojo.
  El botón *Atrás* no la quita: solo se para **escaneando la pegatina correcta** o **cancelando la toma**.
- **Cancelar toma**: desde la lista ("Cancelar toma") o desde la propia alarma, con confirmación. Queda en el historial.
- **Historial** de tomas, cancelaciones y cambios.
- Se reprograma sola tras reiniciar el móvil o cambiar la hora; si al encender ya está fuera de plazo, suena.
- Botón **Probar alarma** para comprobar que suena y que la pegatina la detiene (la prueba no marca la toma).

## Compilar
1. Abre la carpeta `MediTag` en Android Studio (Ladybug o posterior) y deja que sincronice Gradle.
2. Conecta el móvil con depuración USB y pulsa *Run*.
   Requisitos: Android 8.0+ (minSdk 26), con NFC.

## Primer uso
Al abrir, la app muestra avisos para conceder lo necesario: notificaciones, alarma a pantalla
completa (Android 14+), alarmas exactas y **quitar la optimización de batería**. Concédelos
todos; en Xiaomi/Huawei/Samsung conviene además permitir el *inicio automático* de la app.

## Notas
- La mayoría de móviles **solo leen NFC con la pantalla desbloqueada**; la alarma pide desbloquear.
- La antena NFC suele estar en el centro de la parte trasera.
- Rangos que cruzan la medianoche (p.ej. 23:00–01:00) no están soportados; usa 23:00–23:59.
- Para varias tomas al día del mismo medicamento, añádelo varias veces con la misma pegatina y distinto rango.

## Estructura
| Archivo | Qué hace |
|---|---|
| `Medication.kt` | Modelo y almacenamiento (SharedPreferences/JSON) + historial |
| `DoseManager.kt` | Lógica al leer una etiqueta, registrar o cancelar tomas; utilidades NFC |
| `AlarmScheduler.kt` | Programa recordatorio y alarma (AlarmManager), receptores de alarma y arranque |
| `AlarmService.kt` | Servicio en primer plano: sonido, vibración, volumen máximo |
| `AlarmActivity.kt` | Pantalla de alarma a pantalla completa con lector NFC |
| `MainActivity.kt` | Lista, editor, avisos de permisos y lectura NFC |

## Instalar en el móvil sin Android Studio
Cada subida a `main` compila el APK automáticamente (pestaña **Actions**) y lo publica en
**Releases → "MediTag (última compilación)"**. Ábrelo desde el navegador del móvil,
descarga `MediTag.apk` y permite "instalar apps de origen desconocido" cuando lo pida.
