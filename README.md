# ZIMA26

Cámara para Android que revela tus fotos como si fueran fotografías antiguas: grano de
película, rayones, polvo, viñeta, borde quemado y rasgado, y texto escrito a mano.

## Funciones

- **Cámara con filtro en vivo** (OpenGL ES): la vista previa ya muestra el estilo elegido.
- **Revelado completo a resolución máxima** en segundo plano, con animación de revelado.
- **7 estilos de época:** Sepia 1943, Blanco y negro, Daguerrotipo, Cianotipia,
  Polaroid 70s, Kodachrome 60s y Postal coloreada.
- **Borde quemado y rasgado** irregular, distinto en cada foto.
- **Desgaste físico opcional:** pliegues, manchas de agua o café, esquina doblada, cinta adhesiva.
- **Texto manuscrito** y **fecha** con fuente de escritura a mano.
- **Modo Pro:** balance de blancos, enfoque manual, velocidad, ISO y EV.
- **Revelar fotos de la galería**, una o varias a la vez, y **«Compartir → Revelar con ZIMA26»**
  desde cualquier app.
- **Otra versión** (nuevo revelado aleatorio) y **comparador antes/después**.
- **Temporizador** (3 s / 10 s) y **disparo con los botones de volumen**.
- **Álbum** con las fotos reveladas, estilo álbum antiguo de hojas negras.
- Guardado en `Pictures/ZIMA26` con fecha EXIF; compartir la foto o la propia app.

## Stack

- Kotlin 2.4, Jetpack Compose (BOM 2026.09) y Material 3
- CameraX 1.6 (core, camera2, lifecycle, view, extensions) + Camera2 interop para el modo Pro
- OpenCV 4.14 (CLAHE, filtro bilateral, desenfoques, dibujo de rayones)
- AGP 9.4, Gradle 9.8.1, compileSdk/targetSdk 37, minSdk 26, JDK 17

## Estructura

```
app/src/main/java/com/zima26/app/
├── Zima26App.kt                 Inicializa OpenCV
├── MainActivity.kt              Actividad única; recibe fotos compartidas y teclas de volumen
├── camera/
│   ├── CameraController.kt      CameraX: captura máxima calidad, extensiones, flash, enfoque, zoom, modo Pro
│   └── gl/VintagePreviewView.kt Vista previa OpenGL ES 2.0 con el filtro en vivo
├── filter/
│   ├── WarPhotoFilter.kt        Pipeline completo del revelado
│   ├── WearEffects.kt           Exposición irregular, viñeta, rayones, polvo
│   ├── StyleToning.kt           Virado según el estilo de época
│   ├── PhysicalWear.kt          Pliegues, manchas, esquina doblada, cinta adhesiva
│   ├── BurntEdge.kt             Borde quemado y rasgado
│   └── HandwrittenText.kt       Texto y fecha manuscritos
├── io/                          Carga segura de imágenes, guardado (MediaStore) y álbum
├── data/                        Ajustes (DataStore) y modo Pro
├── viewmodel/MainViewModel.kt   Estado y revelado en segundo plano (MVVM)
└── ui/                          Pantallas Compose: cámara, resultado, ajustes, álbum
```

## Compilar e instalar

Abre la carpeta en Android Studio y pulsa **Run**, o desde la terminal:

```bash
./gradlew assembleDebug      # genera app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug       # instala en el teléfono conectado por USB
```

## Notas

- El revelado trabaja con un máximo de ~13 MP para no agotar la memoria; las fotos más grandes
  se reducen antes de procesarlas.
- La vista previa en vivo es una versión simplificada del filtro; el revelado final aplica el
  pipeline completo.

## Licencias de las fuentes

- Special Elite, de Astigmatic: Apache License 2.0
- Homemade Apple, de Font Diner: Apache License 2.0
