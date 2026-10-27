plugins {
	java
	application
	id("com.gradleup.shadow") version "9.6.1"
}

group = "com.vampireteeth"
version = "0.0.1-SNAPSHOT"

repositories {
	mavenCentral()
}

dependencies {
	// implementation("org.springframework.boot:spring-boot-starter")
	// testImplementation("org.springframework.boot:spring-boot-starter-test")
	implementation("com.lmax:disruptor:4.0.0")
	implementation("org.agrona:agrona:2.6.1")
	testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

application {
	mainClass = "com.vampireteeth.trading.Main"
}

tasks.shadowJar {
	archiveClassifier.set("")  // no "-all" suffix
	mergeServiceFiles()
}
tasks.withType<Test> {
	useJUnitPlatform()
}
