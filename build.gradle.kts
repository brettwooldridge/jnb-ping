import org.gradle.jvm.tasks.Jar
import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val kotlin_version : String by extra

repositories {
   mavenCentral()
   jcenter()
}

// Use of buildscript {} necessary due to https://github.com/Kotlin/dokka/issues/146
buildscript {
   var kotlin_version : String by extra
   kotlin_version = "1.1.51"
   repositories {
      jcenter()
   }

   dependencies {
      classpath("org.jetbrains.dokka:dokka-gradle-plugin:0.9.15")
      classpath(kotlinModule("gradle-plugin", kotlin_version))
   }
}

apply {
   plugin("org.jetbrains.dokka")
   plugin("kotlin")
   plugin("maven")
   plugin("signing")
}

plugins {
   `build-scan`
   `maven-publish`
   kotlin("jvm", "1.1.4-3")
}

group = "com.zaxxer"
version = "0.9.0"

dependencies {
   implementation(kotlin("stdlib", "1.1.4-3"))
   compile("com.github.jnr:jnr-posix:3.0.41")
   compile("it.unimi.dsi:fastutil:8.1.0")
   testImplementation("junit:junit:4.12")
   compile(kotlinModule("stdlib-jre8", kotlin_version))
}

buildScan {
   setLicenseAgreementUrl("https://gradle.com/terms-of-service")
   setLicenseAgree("yes")

   publishAlways()
}

// Configure existing Dokka task to output HTML to typical Javadoc directory
val dokka by tasks.getting(DokkaTask::class) {
   outputFormat = "html"
   outputDirectory = "$buildDir/javadoc"
}

// Create dokka Jar task from dokka task output
val dokkaJar by tasks.creating(Jar::class) {
   group = JavaBasePlugin.DOCUMENTATION_GROUP
   classifier = "javadoc"
   // dependsOn(dokka) not needed; dependency automatically inferred by from(dokka)
   from(dokka)
}

// Create sources Jar from main kotlin sources
val sourcesJar by tasks.creating(Jar::class) {
   group = JavaBasePlugin.DOCUMENTATION_GROUP
   classifier = "sources"
   from(java.sourceSets["main"].allSource)
}

publishing {
   publications {
      create("default", MavenPublication::class.java) {
         from(components["java"])

         artifact(sourcesJar)
         artifact(dokkaJar)
      }
   }
   repositories {
      maven {
         url "https://oss.sonatype.org/service/local/staging/deploy/maven2"
      }
   }
}

val compileKotlin : KotlinCompile by tasks
compileKotlin.kotlinOptions {
   jvmTarget = "1.8"
}
val compileTestKotlin : KotlinCompile by tasks
compileTestKotlin.kotlinOptions {
   jvmTarget = "1.8"
}
