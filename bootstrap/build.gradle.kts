plugins {
    id("flightaggregator.java-conventions")
}

dependencies {
    implementation(project(":application"))
    implementation(project(":adapters:providers"))
    implementation(project(":adapters:persistence"))
    implementation(project(":adapters:web"))
}
