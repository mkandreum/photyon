<div align="center">

# Photyon

**Tus fotos, ordenadas por lo que viviste**

*Un producto de [Xyon Platforms](https://github.com/mkandreum)*

![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=flat&logo=kotlin&logoColor=white)
![Android](https://img.shields.io/badge/Android-3DDC84?style=flat&logo=android&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-4285F4?style=flat&logo=jetpackcompose&logoColor=white)

</div>

---

## ¿Qué es Photyon?

Photyon es una galería para Android que muestra las fotos y vídeos del móvil por momentos en lugar de como una cuadrícula de archivos. Funciona sin conexión: nada sale del dispositivo.

## Funcionalidades

- **Momentos**: mosaico por días; pellizca para pasar a meses y años
- **Dial de tiempo**: salta por los años desde el borde de la pantalla
- **La mesa**: visor con luz ambiental y tira de película con lupa; desliza hacia arriba para ver los detalles y hacia abajo para cerrar
- **Vídeo** integrado en el visor
- **Selección** arrastrando, con acciones en lote
- **Recuerdos** de estos días en años anteriores
- **Álbumes** del dispositivo y propios, favoritas y Oculto protegido con el bloqueo del móvil
- **Búsqueda** por mes, año, álbum, nombre o tipo
- **Ajustes de imagen** sin modificar el archivo original
- **Papelera** del sistema al eliminar

## Stack técnico

| Capa | Tecnología |
|------|----------|
| Lenguaje | Kotlin |
| Plataforma | Android (minSdk 34, targetSdk 36) |
| UI | Jetpack Compose + Material 3, sistema de diseño Noir |
| Imágenes | Coil con miniaturas persistentes |
| Vídeo | Media3 ExoPlayer |
| Catálogo | MediaStore |
| Datos del usuario | Room |

## Compilar

```bash
./gradlew :app:assembleRelease
```

Sin clave de subida configurada, la build release se firma con `debug.keystore`.

> © Xyon Platforms — Todos los derechos reservados
