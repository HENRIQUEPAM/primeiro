plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // Lê o google-services.json. Se você ainda não tem o arquivo, comente esta
    // linha para o projeto compilar — mas Auth, Firestore e FCM não funcionarão.
    alias(libs.plugins.google.services)
}

// Assinatura de release: lida de variáveis de ambiente, nunca de um arquivo
// versionado — mesmo princípio do google-services.json (segredo real vem de
// fora do repositório), mas aqui não há "placeholder aceitável": sem os três
// valores, o build de release simplesmente sai sem assinatura (compila, não
// instala) em vez de usar uma chave de mentira que alguém poderia confundir
// com a de verdade. Ver .github/workflows/release-build.yml e o arquivo de
// credenciais entregue com a keystore.
val releaseKeystorePath: String? = System.getenv("RELEASE_KEYSTORE_PATH")
val releaseKeystorePassword: String? = System.getenv("RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAlias: String? = System.getenv("RELEASE_KEY_ALIAS")
val hasReleaseSigning: Boolean =
    !releaseKeystorePath.isNullOrBlank() && !releaseKeystorePassword.isNullOrBlank() && !releaseKeyAlias.isNullOrBlank()

android {
    namespace = "com.portaretrato.app"
    compileSdk = 35

    defaultConfig {
        // applicationId diferente do app de produção (com.portaretrato.app) de
        // propósito: assim este projeto instala LADO A LADO com o Porta Retrato
        // já publicado, em vez de substituí-lo no aparelho de teste.
        applicationId = "com.portaretrato.chamadas"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        ndk {
            // So as ABIs de celular. x86/x86_64 servem apenas a emuladores e
            // respondiam por 32,6 MB dos 67 MB do APK — mais da metade do
            // download, para arquitetura que nenhum aparelho de usuario usa.
            // A Secao 7.2 da especificacao ja pedia essa restricao.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                // Mesma senha para a keystore e para a chave, de propósito —
                // ver o arquivo de credenciais entregue junto da keystore.
                keyPassword = releaseKeystorePassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.material)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.functions)

    implementation(libs.webrtc)

    // Pipeline de reconhecimento facial (pacote `recognition/`). Nao e usado
    // pelas telas de chamada, mas os fontes vivem na mesma source set e
    // portanto sao compilados junto.
    implementation(libs.mlkit.face.detection)
    implementation(libs.tensorflow.lite)
    implementation(libs.androidx.exifinterface)
}
