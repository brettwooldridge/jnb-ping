import org.gradle.jvm.tasks.Jar
import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.kotlin.gradle.tasks.*
import org.gradle.api.publish.maven.plugins.*
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.invoke

val kotlin_version : String by extra

repositories {
   mavenCentral()
   jcenter()
}

// Use of buildscript {} necessary due to https://github.com/Kotlin/dokka/issues/146
buildscript {
   var kotlin_version : String by extra
   kotlin_version = "1.1.60"
   repositories {
      mavenCentral()
      jcenter()
   }

   dependencies {
      classpath("org.jetbrains.dokka:dokka-gradle-plugin:0.9.15")
      classpath(kotlin("gradle-plugin", kotlin_version))
   }
}

apply {
   plugin("kotlin")
   plugin("maven")
   plugin("maven-publish")
   plugin("org.jetbrains.dokka")
   plugin("signing")
}

plugins {
   `build-scan`
   signing
   `kotlin-dsl`
   `maven-publish`
//   kotlin("jvm", kotlin_version)
}

group = "com.zaxxer"
version = "0.9.0"

dependencies {
   implementation(kotlin("stdlib", kotlin_version))
   compile("com.github.jnr:jnr-posix:3.0.41")
   compile("it.unimi.dsi:fastutil:8.1.0")
   testImplementation("junit:junit:4.12")
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

artifacts {
   listOf(dokkaJar, sourcesJar)
}

tasks {
   logger.info("userName: " + project.property("username"))
   "uploadArchives"(Upload::class) {

      repositories {
         withConvention(MavenRepositoryHandlerConvention::class) {
            publishing {
               (publications) {
                  "mavenSources"(MavenPublication::class) {
                     from(components["java"])
                     artifact(dokkaJar)
                     artifact(sourcesJar)
                  }
               }
            }

            mavenDeployer {
               withGroovyBuilder {
                  "repository"("url" to uri("https://oss.sonatype.org/service/local/staging/deploy/maven2/")) {
                     "authentication"("userName" to project.property("username"), "password" to project.property("password"))
                  }
                  "snapshotRepository"("url" to uri("https://oss.sonatype.org/content/repositories/snapshots/")) {
                     "authentication"("userName" to project.property("username"), "password" to project.property("password"))
                  }
                  "beforeDeployment" {
                     if (signing.isRequired)
                        signing.signPom(delegate as MavenDeployment)
                  }
               }

               pom.project {
                  withGroovyBuilder {
                     "parent" {
                        "groupId"("org.gradle")
                        "artifactId"("kotlin-dsl")
                        "version"("1.0")
                     }

                     "scm" {
                        "connection"("scm:git:https://github.com/brettwooldridge/jnb-ping")
                        "developerConnection"("scm:git:https://github.com/brettwooldridge/jnb-ping")
                        "url"("https://github.com/brettwooldridge/jnb-ping")
                     }

                     "licenses" {
                        "license" {
                           "name"("The Apache Software License, Version 2.0")
                           "url"("http://www.apache.org/licenses/LICENSE-2.0.txt")
                           "distribution"("repo")
                        }
                     }
                  }
               }
            }
         }
      }
   }
}

val compileKotlin : KotlinCompile by tasks
compileKotlin.kotlinOptions {
   jvmTarget = "1.8"
   freeCompilerArgs = listOf(
         "Xno-param-assertions",
         "Xno-call-assertions",
         "Xno-receiver-assertions"
   )
}

val compileTestKotlin : KotlinCompile by tasks
compileTestKotlin.kotlinOptions {
   jvmTarget = "1.8"
}

