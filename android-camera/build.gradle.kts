// build.gradle.kts (raiz) — só declara os plugins; cada módulo aplica os seus.
plugins {
    alias(libs.plugins.android.application) apply false
    // O AGP 9 já traz suporte embutido a Kotlin. Declarar o plugin aqui (sem aplicar)
    // apenas fixa a VERSÃO do compilador Kotlin usada pelo AGP.
    alias(libs.plugins.kotlin.android) apply false
}
