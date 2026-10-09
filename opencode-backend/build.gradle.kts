dependencies {
    implementation(project(":opencode-shared"))
    // Gson 由 IDE 提供（2026.1 起自带 2.13.x），compileOnly 只参与编译，不随插件打包
    compileOnly("com.google.code.gson:gson:2.13.2")

    // 事件流（SSE）客户端：平台不提供 SSE 能力，需随插件打包（okio 由 okhttp 传递带入）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    // 单测：本模块的仓储类（SessionCatalog 等）是 internal，只有同模块的 test 源集能访问，
    // 故测试放在本模块而非根项目。gson 是 compileOnly、不进 test 运行时，需显式补一次。
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.code.gson:gson:2.13.2")
}