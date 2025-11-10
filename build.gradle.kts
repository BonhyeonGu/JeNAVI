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
	maven { url = uri("https://repo.osgeo.org/repository/release/") } // OSGeo
	maven { url = uri("https://maven.geo-solutions.it/") }            // GeoSolutions (백업)
}

dependencies {
	// --- Jena ---
	implementation("org.apache.jena:apache-jena-libs:5.5.0")
	implementation("org.apache.jena:jena-geosparql:5.5.0")

	// Apache SIS (모두 1.4로 통일)
	implementation("org.apache.sis.core:sis-referencing:1.4")
	implementation("org.apache.sis.core:sis-metadata:1.4")
	implementation("org.apache.sis.non-free:sis-embedded-data:1.4")

	// Derby (EPSG 임베디드 DB용)
	implementation("org.apache.derby:derby:10.17.1.0")

	// (선택) Jenax
	implementation("org.aksw.jenax:jenax-arq-plugins-bundle:5.4.0-1")

	// --- 나머지 기존 의존성 ---
	implementation("org.springframework.boot:spring-boot-starter")
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
	implementation("org.springframework.boot:spring-boot-starter-websocket")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	testImplementation("org.springframework.boot:spring-boot-starter-test")

	implementation("org.json:json:20231013")
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	implementation("org.apache.kafka:kafka-clients:3.7.0")
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