plugins {
	java
	application
}

group = "com.vampireteeth"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

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

application {
	mainClass = "com.vampireteeth.trading.Main"
}

tasks.withType<Test> {
	useJUnitPlatform()
}
