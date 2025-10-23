import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

var group = "com.zaxxer"
var version = "3.0.0"
var description = "Java Non-Blocking Ping (ICMP)"

project.group = group
project.version = version
project.description = description

// Credentials used for publishing
val ossrhUserName = findProperty("username") as String?
val ossrhPassword = findProperty("password") as String?

plugins {
	kotlin("jvm") version "2.1.20"
	jacoco
	`java-library`
	`maven-publish`
	signing
	id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
}

sourceSets {
	main {
		kotlin.srcDirs("src/main/kotlin")
	}

	test {
		kotlin.srcDirs("src/test/kotlin")
	}
}

java {
	sourceCompatibility = JavaVersion.VERSION_21
	targetCompatibility = JavaVersion.VERSION_21
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

kotlin {
	jvmToolchain(21)
	compilerOptions {
		jvmTarget = JvmTarget.JVM_21

		// Disable some safety checks for better performance
		freeCompilerArgs = listOf(
			"-Xno-param-assertions",
			"-Xno-call-assertions",
			"-Xno-receiver-assertions",
		)
	}
}

tasks {
	test {
		systemProperty("java.util.logging.config.file", "$projectDir/src/test/resources/logging.properties")

		useJUnitPlatform()

		// Log everything
		testLogging.events = TestLogEvent.values().toSet()

		finalizedBy(jacocoTestReport)
	}

	javadoc {
		if (JavaVersion.current().isJava9Compatible) {
			(options as StandardJavadocDocletOptions).addBooleanOption("html5", true)
		}
	}

	jacocoTestReport {
		reports {
			xml.required = true
			html.required = true
		}

		dependsOn(test)
	}
}

dependencies {
	implementation("com.github.jnr:jnr-posix:3.1.20")
	implementation("com.carrotsearch:hppc:0.10.0")

	testImplementation("org.junit.jupiter:junit-jupiter-api:5.12.1")
	testImplementation("org.junit.jupiter:junit-jupiter-engine:5.12.1")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.1")
}

configurations {
	all {
		// Fail if there are conflicting versions of the same dependency in a dependency graph
		resolutionStrategy.failOnVersionConflict()
	}
}

if (ossrhUserName == null || ossrhPassword == null) {
	repositories {
		mavenCentral()
	}
} else {
	repositories {
		mavenCentral {
			credentials {
				username = ossrhUserName
				password = ossrhPassword
			}
		}
		maven {
			name = "repository"
			url = uri("https://ossrh-staging-api.central.sonatype.com")
			credentials {
				username = ossrhUserName
				password = ossrhPassword
			}
		}
		maven {
			name = "snapshotRepository"
			url = uri("https://oss.sonatype.org/content/repositories/snapshots/")
			credentials {
				username = ossrhUserName
				password = ossrhPassword
			}
		}
	}
}

// Publishing

val javadocJar by tasks.registering(Jar::class) {
	archiveClassifier = "javadoc"
	from(tasks.javadoc.get())
}

val sourcesJar by tasks.registering(Jar::class) {
	archiveClassifier = "sources"
	from(sourceSets.main.get().allSource)
}

val testsJar by tasks.registering(Jar::class) {
	archiveClassifier = "tests"
	from(sourceSets.test.get().allSource)
}

publishing {
	publications {
		create<MavenPublication>("mavenJava") {
			this.groupId = group
			this.artifactId = project.name
			this.version = version
			from(components["java"])
			artifact(sourcesJar)
			artifact(javadocJar)
			pom {
				name = project.name
				description = project.description
				packaging = "jar"
				url = "https://github.com/brettwooldridge/jnb-ping"
				developers {
					developer {
						id = "brettwooldridge"
						name = "Brett Wooldridge"
						email = "brett.wooldridge@gmail.com"
					}
				}
				licenses {
					license {
						name = "Apache License"
						url = "https://www.apache.org/licenses/LICENSE-2.0"
					}
				}
				scm {
					connection = "scm:git:https://github.com/brettwooldridge/jnb-ping.git"
					developerConnection = "scm:git:git@github.com:brettwooldridge/jnb-ping.git"
					url = "https://github.com/brettwooldridge/jnb-ping"
				}
			}
		}
	}
}

artifacts {
	add("archives", javadocJar)
	add("archives", sourcesJar)
}

signing {
	if (gradle.startParameter.taskNames.any { it.contains("publish", ignoreCase = true)}) {
		useGpgCmd()
		sign(configurations.archives.get())
		sign(publishing.publications["mavenJava"])
	}
}

nexusPublishing {
	repositories {
		sonatype {
			username.set(ossrhUserName)
			password.set(ossrhPassword)
		}
	}
}
