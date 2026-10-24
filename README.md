# Limpia RAM

App de Android que muestra el uso de RAM en tiempo real y tiene un botón para cerrar apps en segundo plano (como Mem Reduct en PC, con las limitaciones de Android).

Requiere Android 8.0 o superior.

## Cómo obtener el APK

1. Subí todo este proyecto a un repositorio de GitHub (incluida la carpeta `.github`).
2. Entrá a la pestaña **Actions** del repositorio. Se ejecuta solo el workflow **Compilar APK** (tarda unos 5 minutos).
3. Cuando termine, abrí esa ejecución y bajá el archivo **LimpiaRAM-apk** desde la sección *Artifacts*.
4. Descomprimí el zip, pasá `app-debug.apk` al celular e instalalo (hay que permitir "instalar apps de origen desconocido").

Si querés volver a compilar sin cambiar nada: Actions → Compilar APK → **Run workflow**.

## Cómo funciona

- Lee la memoria con `ActivityManager.getMemoryInfo()`.
- El botón recorre las apps instaladas (no las del sistema) y llama a `killBackgroundProcesses()` para cada una.
- Android no deja que una app común cierre procesos en primer plano ni del sistema, y muchas apps se reinician solas, así que el efecto es limitado.
