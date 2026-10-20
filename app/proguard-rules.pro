# Lecture JSON via JsonElement : pas de réflexion, pas de règle nécessaire.

# jhdf (lecture des fichiers radar HDF5) charge ses filtres de décompression dynamiquement.
-keep class io.jhdf.** { *; }
-dontwarn org.slf4j.**

# Noms de classes et numéros de ligne gardés : le rapport de plantage affiché dans l'appli reste lisible
# (la publication ne garde pas la table de correspondance de R8). Le code inutilisé est toujours retiré.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
