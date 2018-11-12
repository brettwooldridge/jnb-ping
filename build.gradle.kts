import org.gradle.api.internal.HasConvention
import org.gradle.api.tasks.bundling.Jar
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile


description = "Java Non-Blocking Ping (ICMP)"
group = "com.zaxxer"
version = "0.9.2"

plugins {
	kotlin("jvm") version "1.3.0"
	jacoco
	`java-library`
	maven
	`maven-publish`
	signing
	// id("com.github.ben-manes.versions") version "0.20.0"
}

sourceSets {
	getByName("main") {
		kotlin.srcDirs("Sources")
	}

	getByName("test") {
		kotlin.srcDirs("Tests/Sources")
		resources.srcDirs("Tests/Resources")
	}
}

java {
	sourceCompatibility = JavaVersion.VERSION_1_8
	targetCompatibility = JavaVersion.VERSION_1_8
}

jacoco {
	toolVersion = "0.8.2"
}

tasks {
	val check by this

	withType<KotlinCompile> {
		sourceCompatibility = "1.8"
		targetCompatibility = "1.8"

		kotlinOptions.jvmTarget = "1.8"
	}

	withType<Test> {
		useJUnit()

		testLogging {
			events("FAILED", "PASSED", "SKIPPED")
		}
	}

	withType<JacocoReport> {
		reports {
			xml.isEnabled = true
			html.isEnabled = false
		}

		check.dependsOn(this)
	}
}

dependencies {
	api(kotlin("reflect"))
	api(kotlin("stdlib-jdk8"))
    compile("com.github.jnr:jnr-posix:3.0.41")
    compile("it.unimi.dsi:fastutil:8.1.0")

    testImplementation("junit:junit:4.12")
}

configurations {
	all {
		resolutionStrategy {
			force("org.jetbrains.kotlin:kotlin-reflect:1.3.0")
			force("org.jetbrains.kotlin:kotlin-stdlib:1.3.0")

			failOnVersionConflict()
		}
	}

	// getByName("examplesImplementation") {
	// 	extendsFrom(configurations["api"])
	// }
}

repositories {
	mavenCentral()
	jcenter()
}

val SourceSet.kotlin
	get() = withConvention(KotlinSourceSet::class) { kotlin }


// publishing

val javadoc = tasks["javadoc"] as Javadoc
val javadocJar by tasks.creating(Jar::class) {
	classifier = "javadoc"
	from(javadoc)
}

val sourcesJar by tasks.creating(Jar::class) {
	classifier = "sources"
	from(sourceSets["main"].allSource)
}

val testsJar by tasks.creating(Jar::class) {
	classifier = "tests"
	from(sourceSets["test"].allSource)
}

publishing {
	publications {
		create<MavenPublication>("default") {
			from(components["java"])
			artifact(sourcesJar)
		}
	}
}

val ossrhUserName = findProperty("username") as String?
val ossrhPassword = findProperty("password") as String?
if (ossrhUserName != null && ossrhPassword != null) {
	artifacts {
		add("archives", javadocJar)
		add("archives", sourcesJar)
	}

	signing {
		sign(configurations.archives.get())
	}

	tasks {
		"uploadArchives"(Upload::class) {
			repositories {
				withConvention(MavenRepositoryHandlerConvention::class) {
					mavenDeployer {
						withGroovyBuilder {
							"beforeDeployment" { signing.signPom(delegate as MavenDeployment) }

							"repository"("url" to "https://oss.sonatype.org/service/local/staging/deploy/maven2/") {
								"authentication"("userName" to ossrhUserName, "password" to ossrhPassword)
							}

							"snapshotRepository"("url" to "https://oss.sonatype.org/content/repositories/snapshots/") {
								"authentication"("userName" to ossrhUserName, "password" to ossrhPassword)
							}
						}

						pom.project {
							withGroovyBuilder {
								"name"(project.name)
								"description"(project.description)
								"packaging"("jar")
								"url"("https://github.com/brettwooldridge/jnb-ping")
								"developers" {
									"developer" {
										"id"("brettwooldridge")
										"name"("Brett Wooldridge")
										"email"("brett.wooldridge@gmail.com")
									}
								}
								"licenses" {
									"license" {
										"name"("Apache License")
										"url"("https://www.apache.org/licenses/LICENSE-2.0")
									}
								}
								"scm" {
									"connection"("scm:git:https://github.com/brettwooldridge/jnb-ping.git")
									"developerConnection"("scm:git:git@github.com:brettwooldridge/jnb-ping.git")
									"url"("https://github.com/brettwooldridge/jnb-ping")
								}
							}
						}
					}
				}
			}
		}
	}
}

// val compileKotlin : KotlinCompile by tasks
// compileKotlin.kotlinOptions {
//    jvmTarget = "1.8"
//    freeCompilerArgs = listOf(
//          "Xno-param-assertions",
//          "Xno-call-assertions",
//          "Xno-receiver-assertions"
//    )
// }

val compileTestKotlin : KotlinCompile by tasks
compileTestKotlin.kotlinOptions {
   jvmTarget = "1.8"
}
