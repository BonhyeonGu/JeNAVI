import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
	id("org.springframework.boot") version "3.1.2"
	id("io.spring.dependency-management") version "1.1.2"
	kotlin("jvm") version "1.8.22"
	kotlin("plugin.spring") version "1.8.22"
}

group = "9Bon"
version = "0.0.1-SNAPSHOT"

java {
	sourceCompatibility = JavaVersion.VERSION_17
}

repositories {
	mavenCentral()
}

dependencies {
	// Spring
	implementation("org.springframework.boot:spring-boot-starter")
	implementation("org.jetbrains.kotlin:kotlin-reflect") 
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
	implementation("org.springframework.boot:spring-boot-starter-websocket")

	// Logback
	//implementation("ch.qos.logback:logback-classic:1.4.12") // Logback Classic
	//implementation("ch.qos.logback:logback-core:1.4.12")

	// Jena
    implementation("org.apache.jena", "apache-jena-libs", "5.0.0")

	// JSON Read
	implementation("org.json:json:20231013")

	// Kotlin + Jackson 연동 모듈
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	implementation("org.apache.kafka:kafka-clients:3.7.0") // Kafka 클라이언트
}

tasks.withType<KotlinCompile> {
	kotlinOptions {
		freeCompilerArgs += "-Xjsr305=strict"
		jvmTarget = "17"
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}

tasks.withType(JavaExec::class.java) {
    jvmArgs("-Xms32g", "-Xmx32g")
	//export JAVA_OPTS="-Xms64g -Xmx64g
	//$env:GRADLE_OPTS="-Xmx800g"
}

tasks.register<JavaExec>("debug") {
    mainClass.set("Jenavi.ApplicationKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005")
}