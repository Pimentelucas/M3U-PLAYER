plugins {
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20" apply false
}

// Saídas de build num caminho curto (opcional): o Windows limita caminhos a 260 caracteres e as pastas
// intermediárias do Android são profundas. Para usar, coloque m3u.buildRoot=C:/M3UBuild no local.properties
// (específico de cada PC, fora do Git) ou no gradle.properties da sua pasta de usuário.
val localProps = java.util.Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
(providers.gradleProperty("m3u.buildRoot").orNull ?: localProps.getProperty("m3u.buildRoot"))?.let { root ->
    allprojects {
        layout.buildDirectory.set(file("$root/${project.name}"))
    }
}
