import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.springframework.boot.gradle.tasks.bundling.BootJar
import org.gradle.api.file.DuplicatesStrategy

plugins {
	id("org.springframework.boot") version "3.1.2"
	id("io.spring.dependency-management") version "1.1.2"
	kotlin("jvm") version "1.8.22"
	kotlin("plugin.spring") version "1.8.22"
}

group = "9Bon"
version = "0.0.2-SNAPSHOT"

java {
	sourceCompatibility = JavaVersion.VERSION_17
}

repositories {
	mavenCentral()
	maven { url = uri("https://repo.osgeo.org/repository/release/") } // OSGeo
	maven { url = uri("https://maven.geo-solutions.it/") }            // GeoSolutions (백업)
}

/**
 * 문제 원인:
 *  - jaxb-core-4.0.3.jar 가 서로 다른 groupId로 2개 들어옴
 *    (com.sun.xml.bind:jaxb-core) + (org.glassfish.jaxb:jaxb-core)
 *  - bootJar는 BOOT-INF/lib에 의존성 jar를 "복사"하므로 파일명 중복이면 실패
 *
 * 해결:
 *  - com.sun.xml.bind 쪽을 제외하고(org.glassfish 쪽만 사용) 중복 제거
 */
configurations.configureEach {
	exclude(group = "com.sun.xml.bind", module = "jaxb-core")
	exclude(group = "com.sun.xml.bind", module = "jaxb-impl")
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

	// (선택) exclude 후 JAXB 런타임이 확실히 필요하면 아래를 명시해도 됨
	// implementation("org.glassfish.jaxb:jaxb-runtime:4.0.3")
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
	jvmArgs("-Xms64g", "-Xmx64g")
	//export JAVA_OPTS="-Xms64g -Xmx64g"
	//$env:GRADLE_OPTS="-Xmx800g"
}

/**
 * (안전망) 혹시 다른 jar 중복이 남아도 bootJar가 바로 죽지 않게.
 * exclude로 해결되면 없어도 되지만, 개발 중엔 편합니다.
 */
tasks.named<BootJar>("bootJar") {
	duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.register<JavaExec>("debug") {
	mainClass.set("Jenavi.ApplicationKt")
	classpath = sourceSets["main"].runtimeClasspath
	jvmArgs("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005")
}
