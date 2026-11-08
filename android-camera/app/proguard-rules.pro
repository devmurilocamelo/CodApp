# RootEncoder usa reflexão em poucos pontos; manter as classes públicas evita
# quebras sutis no build de release.
-keep class com.pedro.** { *; }
-dontwarn com.pedro.**
# Ktor (dependência do RootEncoder) referencia classes opcionais da JVM.
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
