# MediTag — control de medicación con etiquetas NFC (NTAG215)

App Android (Kotlin + Jetpack Compose) que obliga a registrar cada toma acercando
al móvil la pegatina NFC pegada al medicamento. Solo se usa el **UID** de la
etiqueta, no hace falta grabar nada en ella.

## Funciones
- **Medicamentos**: nombre, pegatina NFC asociada (botón *Escanear*), **pauta** y **rango horario** (desde / hasta).
- **Pautas**: cada día · días de la semana (L–D) · cada N días (días alternos = 2) · fechas concretas
  marcadas en un calendario · **a demanda** (sin horario ni alarma: cada escaneo registra una toma y se cuentan las del día).
- **Ajustes de la alarma** (botón 🔔 Alarma): sonido: pitido estridente por defecto, un tono de alarma del móvil o cualquier archivo de audio.
- **Registrar toma**: acercar la pegatina con la app abierta *o cerrada* (Android abre la app al detectarla).
  Se acepta desde 30 min antes del inicio del rango (`EARLY_MARGIN_MIN` en `DoseManager.kt`).
- **Recordatorio** (notificación) al empezar el rango.
- **Alarma desagradable** al acabar el rango si no se ha tomado: onda cuadrada aguda
  (2,9/3,5 kHz) a volumen de alarma máximo, vibración continua y pantalla parpadeando en rojo.
  El botón *Atrás* no la quita: solo se para **escaneando la pegatina correcta** o **cancelando la toma**.
- **Duración máxima** de la alarma en minutos (0 = sin límite) y **repeticiones** (cada X min, N veces).
  Si nadie responde, la toma queda como *no tomada*, con notificación fija y una **pantalla de aviso
  sobre la pantalla de bloqueo** que es lo primero que se ve al coger el móvil. Escanear después la
  registra como *tomada con retraso*.
- **No molestar**: con el acceso concedido, la alarma suena aunque No molestar bloquee las alarmas
  (se cambia temporalmente a "solo alarmas" y se restaura al pararla).
- **Cancelar toma**: desde la lista ("Cancelar toma") o desde la propia alarma, con confirmación. Queda en el historial.
- **Historial** de tomas, cancelaciones y cambios, en una pantalla aparte (botón *Historial*).
- **Copia de seguridad**: exportar e importar a un archivo JSON los medicamentos (pauta, pegatina, estado) y el historial.
- **Acerca de**: versión de la app y enlace a este repositorio.
- Se reprograma sola tras reiniciar el móvil o cambiar la hora; si al encender ya está fuera de plazo, suena.
- **Probar alarma** (en 🔔 Alarma): hace sonar la alarma con los ajustes elegidos sin tocar ninguna toma; se para con «Detener prueba» o con cualquier pegatina (sirve para comprobar el lector NFC).

## Guía para usuarios
📄 **[Guía de instalación y uso (PDF)](docs/MediTag-Guia-de-uso.pdf)**: explicada paso a paso para personas
poco habituadas al móvil, con código QR de descarga. Se puede imprimir y repartir libremente.
La fuente está en `docs/guia/` (HTML; el PDF se genera imprimiendo el HTML con Chromium).

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
- Si al guardar un medicamento ya ha pasado el rango de hoy, empieza a contar desde mañana (no suena nada más guardar).

## Estructura
| Archivo | Qué hace |
|---|---|
| `Medication.kt` | Modelo y almacenamiento (SharedPreferences/JSON) + historial |
| `DoseManager.kt` | Lógica al leer una etiqueta, registrar o cancelar tomas; utilidades NFC |
| `AlarmScheduler.kt` | Programa recordatorio y alarma (AlarmManager), receptores de alarma y arranque |
| `AlarmService.kt` | Servicio en primer plano: sonido, vibración, volumen máximo |
| `AlarmActivity.kt` | Pantalla de alarma a pantalla completa con lector NFC |
| `MainActivity.kt` | Lista, avisos de permisos, selector de sonido y lectura NFC |
| `Editor.kt` | Editor de medicamento: pauta, calendario mensual, horario; diálogo de sonido |

## Instalar en el móvil sin Android Studio
Cada subida a `main` compila el APK automáticamente (pestaña **Actions**) y lo publica en
**Releases → "MediTag (última compilación)"**. Ábrelo desde el navegador del móvil,
descarga `MediTag.apk` y permite "instalar apps de origen desconocido" cuando lo pida.

## Firma del APK
Las versiones publicadas se firman en GitHub Actions con una clave privada guardada en los
secretos del repositorio (`SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`,
`SIGNING_KEY_PASSWORD`). La clave no está en el código. Para compilar en local, Android Studio usa
su clave de depuración habitual.

## Licencia
MediTag es software libre: puedes redistribuirlo y/o modificarlo bajo los términos de la
[GNU General Public License v3.0](LICENSE) publicada por la Free Software Foundation.
