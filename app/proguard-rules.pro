# Lecture JSON via JsonElement : pas de réflexion, pas de règle nécessaire.

# jhdf (lecture des fichiers radar HDF5) charge ses filtres de décompression dynamiquement.
-keep class io.jhdf.** { *; }
-dontwarn org.slf4j.**
