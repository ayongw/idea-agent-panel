dependencies {
    implementation(project(":opencode-shared"))
    // Gson 由 IDE 提供（2026.1 起自带 2.13.x），compileOnly 只参与编译，不随插件打包
    compileOnly("com.google.code.gson:gson:2.13.2")

    // 事件流（SSE）客户端：平台不提供 SSE 能力，需随插件打包（okio 由 okhttp 传递带入）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
}