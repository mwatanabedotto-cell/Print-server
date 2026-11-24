# Asistente de Impresión 🖨️

App Android para una **tablet conectada a una impresora**. Los docentes envían
archivos a la tablet **desde su celular o desde una PC** (solo con el navegador,
sin instalar nada), y la tablet los muestra en una cola para imprimirlos con un toque.

```
 Celular / PC del docente ──Wi‑Fi──►  Tablet (app)  ──►  Impresora
   navegador: http://IP:8080          cola + botón       (Wi‑Fi, USB o
   elige archivo, copias…             "Imprimir"          Bluetooth)
```

## Instalación en la tablet

1. En la tablet, abre la página **Releases** de este repositorio y descarga
   `AsistenteImpresion.apk`.
2. Ábrelo. Si Android lo pide, permite **"Instalar apps desconocidas"** para el navegador.
3. Abre **Asistente de Impresión** y acepta las notificaciones.
4. Si aparece el aviso de batería, toca **"Permitir que siga activa"** (así Android no
   cierra la app).
5. Configura la impresora en Android: **Ajustes → Dispositivos conectados → Impresión**.
   Activa el *Servicio de impresión predeterminado* o instala el plugin de la marca
   (HP Smart / HP Print Service, Epson iPrint, Canon Print, Brother, o **Mopria**).

> Tras actualizar el APK no hace falta desinstalar la versión anterior.

## Uso

**Docentes (celular o PC, en la misma red Wi‑Fi):**
escanean el código QR que muestra la tablet o escriben la dirección
(por ejemplo `http://192.168.1.25:8080`), eligen el archivo, escriben su nombre,
copias, doble cara / color y notas, y tocan **Enviar a imprimir**.
En la PC también se puede **arrastrar el archivo** a la página.

**Tablet:** suena una notificación y el archivo aparece en *Pendientes*.

| Tipo de archivo | Qué pasa al tocar **Imprimir** |
|---|---|
| PDF | Se abre el diálogo de impresión de Android (con color y doble cara ya elegidos). |
| JPG, PNG, GIF, WEBP | Se imprime ajustada a la hoja. |
| Word, Excel, PowerPoint, TXT… | Se abre en una app instalada (WPS Office, Google Docs…) → menú ⋮ → *Imprimir*. |

> El número de **copias** se ajusta en el diálogo de impresión de Android (la tablet lo
> recuerda con un aviso). Consejo: pedir a los docentes que envíen **PDF**.

En ⚙️ **Ajustes** puedes poner el nombre de la escuela, un **código de acceso** para que
solo los docentes puedan enviar, el tamaño máximo y cuántos días se guardan los archivos
(se borran solos).

## Recomendaciones

- Deja la tablet **siempre cargando** y con el Wi‑Fi de la escuela.
- Para que la dirección no cambie, reserva una **IP fija** para la tablet en el router.
- La app se inicia sola al encender la tablet.
- El panel solo se puede usar en la propia tablet; desde otros equipos solo se puede
  enviar archivos.

## Desarrollo

Proyecto Android en Java (`minSdk 21`, Android 5.0+). Componentes:

- `WebServer.java` – servidor HTTP embebido (NanoHTTPD) en el puerto 8080.
- `ServerService.java` – servicio en primer plano que mantiene el servidor activo.
- `MainActivity.java` – panel (WebView) e impresión con `PrintManager`.
- `JobStore.java` – cola de trabajos (`jobs.json`) y ajustes.
- `assets/upload.html` – página de los docentes; `assets/panel.html` – panel de la tablet.

Compilar localmente con Android SDK: `./gradlew assembleDebug`.
Cada *push* compila el APK automáticamente con GitHub Actions y lo publica en
**Releases** (`apk-latest` para `main`, `apk-dev` para otras ramas).

`app/debug.keystore` es una clave de **depuración** pública (contraseña `android`):
sirve para que las actualizaciones se instalen encima sin desinstalar.
No la uses para publicar en Google Play.
