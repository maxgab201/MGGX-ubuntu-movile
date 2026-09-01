# MGGX Ubuntu Mobile — Idea y arquitectura

## Objetivo

Construir una terminal Ubuntu para Android comparable en comodidad a Termux, pero centrada en ofrecer un entorno Ubuntu completo desde el primer inicio y una integración progresiva con APIs de Android.

La app no simula una consola: mantiene un root filesystem real de Ubuntu Base y ejecuta binarios Linux mediante PRoot en userspace.

## Arquitectura general

```text
Android App
│
├── UI nativa Android
│   ├── TerminalView
│   ├── barra de teclas extra
│   ├── menú de permisos
│   └── controles de sesión / wake lock
│
├── Terminal engine
│   └── Termux terminal-view / terminal-emulator
│
├── Runtime Linux
│   ├── PRoot ARM64
│   ├── proot loader
│   ├── libtalloc
│   └── libandroid-shmem
│
├── UbuntuInstaller
│   ├── Ubuntu Base 26.04 ARM64
│   ├── extracción segura
│   ├── symlinks
│   ├── hard links materializados
│   ├── validación de archivos esenciales
│   └── instalación transaccional / recuperación
│
└── Integración Android
    ├── /sdcard bind
    ├── Internet
    ├── wake lock
    ├── permisos de archivos
    └── permisos bajo demanda
```

## Estructura del repositorio

```text
.
├── .github/workflows/android.yml
├── app/
│   ├── build.gradle
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/mggx/ubuntu/
│       │   ├── MainActivity.java
│       │   ├── UbuntuInstaller.java
│       │   └── WakeLockService.java
│       └── res/layout/activity_main.xml
├── ci/mggx-debug.keystore.b64
├── build.gradle
├── gradle.properties
├── settings.gradle
├── README.md
└── PROJECT.md
```

Los binarios PRoot y el rootfs de Ubuntu se descargan durante CI y se empaquetan dentro del APK. No se versionan esos binarios pesados en Git.

## Instalación del rootfs

1. El CI obtiene Ubuntu Base oficial y valida su SHA-256.
2. El APK incluye el rootfs como asset opaco `ubuntu-base-arm64.rootfs`.
3. En Android se extrae primero en un directorio temporal.
4. Se procesan symlinks sin seguir enlaces accidentalmente.
5. Los hard links se materializan como copias o enlaces equivalentes para evitar restricciones de Android/SELinux.
6. Se validan `bash`, `env`, `apt`, `passwd` y `os-release`.
7. Solo entonces el rootfs nuevo reemplaza al anterior.
8. Si la app se corta durante el proceso, el próximo inicio limpia/restaura el estado parcial.

## Ejecución

PRoot monta/bindea:

```text
Ubuntu rootfs
  + /dev
  + /proc
  + /sys
  + /sdcard
  + /host
```

La shell principal es `/bin/bash --login`, con `TERM=xterm-256color`.

## Integración Android futura

La meta es agregar un bridge tipo Termux:API integrado en la propia app.

Comandos previstos:

```text
mggx-camera
mggx-mic
mggx-location
mggx-notification
mggx-battery
mggx-clipboard
mggx-vibrate
mggx-intent
mggx-wifi
```

Cada comando hablaría con un servicio Android de la app y pediría permisos peligrosos únicamente cuando esa función se use.

## Roadmap

- [x] Terminal Android funcional.
- [x] PRoot ARM64.
- [x] Ubuntu Base 26.04.
- [x] Rootfs offline dentro del APK.
- [x] Barra de teclas extra.
- [x] Acceso a `/sdcard`.
- [x] Wake lock.
- [x] CI reproducible y verificación SHA-256.
- [ ] Bridge Android ↔ Ubuntu.
- [ ] Sesiones múltiples y persistentes.
- [ ] Preferencias de terminal y temas.
- [ ] Mejor soporte de teclado físico/táctil.
- [ ] Backup/import/export del entorno Ubuntu.
- [ ] Build release firmado para distribución estable.
