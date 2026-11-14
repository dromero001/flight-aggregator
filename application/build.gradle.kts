plugins {
    `java-library`
    id("flightaggregator.java-conventions")
}

dependencies {
    api(project(":domain"))
}
