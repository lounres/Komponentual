kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(versions.kone.automata)
                implementation(versions.kone.util.atomicfu)
                api(versions.kone.collections)
                api(versions.kotlinx.coroutines.core)
            }
        }
    }
}