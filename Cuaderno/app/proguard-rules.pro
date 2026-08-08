# kotlinx.serialization genera serializadores por reflexion sobre las @Serializable
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class cl.aguirre.cuaderno.data.** {
    *** Companion;
}
-keepclasseswithmembers class cl.aguirre.cuaderno.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# El motor de tinta carga codigo nativo
-keep class androidx.ink.** { *; }
-dontwarn androidx.ink.**
