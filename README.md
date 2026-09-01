# MGGX Ubuntu Mobile

Una terminal **Ubuntu real en Android**, ejecutada en userspace mediante **PRoot**, con una interfaz de terminal basada en las librerías de Termux.

> Estado actual: **v0.1.2** — primera versión funcional en ARM64.

## Qué hace

MGGX Ubuntu Mobile busca dar una experiencia parecida a Termux, pero arrancando directamente sobre un entorno Ubuntu completo y con integración progresiva con Android.

### Funciones actuales

- Ubuntu Base 26.04 ARM64 en userspace.
- PRoot empaquetado en el APK.
- Rootfs oficial de Ubuntu incluido en el APK: la instalación inicial no depende de DNS ni de Internet.
- PTY / terminal compatible con ANSI y programas interactivos como `nano`, `vim` y `htop`.
- Barra de teclas extra tipo PC: Esc, Ctrl, Alt, Tab, flechas, Home, End, Page Up/Down y símbolos frecuentes.
- Acceso a `/sdcard`.
- Acceso a Internet desde Ubuntu.
- Wake lock mediante foreground service.
- Solicitud obligatoria de “Acceso a todos los archivos”.
- Cámara, micrófono, ubicación y notificaciones solicitados únicamente cuando se eligen desde el menú.
- Reinicio de sesión y reinstalación del rootfs.
- Instalación segura con validaciones, limpieza de estados parciales y recuperación.

## Descargar

El APK más reciente se publica en **GitHub Releases**.

## Compilar

Requisitos:

- Java 17
- Android SDK 35
- Build Tools 35.0.0
- Gradle 8.10.2

```bash
gradle :app:assembleDebug
```

APK generado:

```text
app/build/outputs/apk/debug/app-debug.apk
```

El workflow de GitHub Actions además descarga y verifica PRoot y Ubuntu Base, comprueba el rootfs dentro del APK terminado, calcula SHA-256 y publica el APK como artifact y Release.

## Arquitectura

La idea, estructura interna y roadmap están documentados en [PROJECT.md](PROJECT.md).

## Limitaciones

Ubuntu comparte el kernel de Android mediante PRoot. No es una VM y no entrega root real sobre Android. Para usar cámara, micrófono, ubicación y otras APIs desde comandos de Ubuntu hace falta un bridge Android ↔ Ubuntu, que forma parte del roadmap.
