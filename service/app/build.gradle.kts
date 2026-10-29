plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":zabbix-client"))
    implementation(project(":domain"))
    implementation(project(":llm"))
    implementation(project(":analysis-graph"))
    implementation(project(":analysis-metrics"))
    implementation(project(":analysis-logs"))
    implementation(project(":analysis-ranking"))
    implementation(project(":analysis-explain"))
    implementation(project(":analysis-evaluation"))
    implementation(project(":analysis-stream"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}
