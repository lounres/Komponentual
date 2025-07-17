kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(versions.kone.util.misc)
                implementation(versions.kone.automata)
                api(versions.kone.state)
                api(versions.kone.collections)
            }
        }
    }
}