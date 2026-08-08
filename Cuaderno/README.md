# Cuaderno

App nativa de notas manuscritas para Android, hecha para una tablet con lápiz
activo. Uso personal.

El objetivo concreto: escribir a mano sin que se note el retardo entre la punta
del lápiz y la tinta en pantalla, que es donde las apps portadas desde iOS se
sienten mal.

## Estado

MVP. Compila y genera APK, pero **todavía no se ha probado en el dispositivo**.

## Hardware objetivo

Lenovo Idea Tab / Tab K11 Gen 2 (TB336ZU) — 11" 2.5K a 90 Hz, MediaTek
Dimensity 6300, Android 16, con **Lenovo Tab Pen Plus** (presión y tilt).

`minSdk` es 29 porque el renderizado con front buffer, que es lo que da la
latencia baja, no existe antes de Android 10.

## Qué hace

- Escritura a mano con presión e inclinación (lápiz, grafito, marcador, resaltador)
- Rechazo de palma y modo solo-lápiz: el dedo hace zoom, nunca dibuja
- Borrador por trazo, incluido el botón-borrador del lápiz
- Deshacer / rehacer
- Zoom y desplazamiento, hasta 8x
- Plantillas: cuadriculado de 5 mm, líneas, puntos, blanco
- Importar un PDF y anotar encima
- Exportar el cuaderno a PDF y compartirlo

Fuera del alcance por ahora: OCR y búsqueda de manuscrito, sincronización en la
nube, reconocimiento de formas, grabación de audio.

## Cómo está construido

Kotlin + Jetpack Compose. El motor de tinta es
[androidx.ink](https://developer.android.com/jetpack/androidx/releases/ink)
(Ink API), que está **en alpha** — la versión está fijada a propósito en
`gradle/libs.versions.toml`.

Tres decisiones que explican la estructura:

**Dos capas de tinta.** El trazo en curso lo dibuja `InProgressStrokesView` en un
front buffer, sin esperar el ciclo normal de composición. Al levantar el lápiz
pasa a la capa "seca", que se redibuja normalmente. Es la misma arquitectura que
usan las apps de notas que se sienten bien.

**Los trazos se guardan en coordenadas de página, no de pantalla.** La unidad es
el punto PDF (1/72"). El zoom vive solo en las matrices de transformación, así
que acercarse no degrada la tinta y exportar a PDF es una copia directa sin
reescalado.

**Almacenamiento en archivos, no base de datos.** Un cuaderno es una carpeta:

```
files/notebooks/<id>/
    index.json          metadatos y orden de páginas
    pages/<pageId>.ink  tinta de cada página, binario propio
    source.pdf          PDF de fondo (opcional)
```

Copiar la carpeta es respaldar el cuaderno. No hay migraciones de esquema que
mantener.

### Sobre el borrador

Cada trazo guarda, además de la geometría del motor, una polilínea simplificada
propia en coordenadas de página. Solo sirve para responder "¿el borrador tocó
este trazo?". Se captura desde los `MotionEvent`, que de todas formas pasan por
la app para el rechazo de palma. Así el borrado no depende de las APIs de
geometría del motor, que están en alpha.

## Compilar

Requiere JDK 21 y el Android SDK (API 36).

```bash
./gradlew assembleDebug
```

El APK queda en `app/build/outputs/apk/debug/`. También lo construye GitHub
Actions en cada push y lo deja como artifact descargable.

Para abrirlo en Android Studio, basta con abrir la carpeta raíz.

## Pendiente antes de usarlo en serio

- [ ] Probarlo en la tablet y medir la latencia real contra la app actual
- [ ] Ajustar la curva de presión al Tab Pen Plus
- [ ] Miniaturas reales de portada en la biblioteca (hoy es un ícono)
- [ ] Selección con lazo para mover y escalar lo escrito
