#!/bin/bash
# 安装 IntelliJ IDEA 2026.2.3 从本地 DMG
# 用于解决 Gradle 下载 IDEA 失败的问题

set -e

DMG_PATH="/Users/jiangguangtao/software/idea-2026.2.3-aarch64.dmg"
INSTALL_DIR="/Applications"
APP_NAME="IntelliJ IDEA.app"

echo "🔍 检查 DMG 文件..."
if [[ ! -f "$DMG_PATH" ]]; then
    echo "❌ DMG 文件不存在: $DMG_PATH"
    exit 1
fi

echo "📦 挂载 DMG..."
MOUNT_POINT=$(hdiutil attach "$DMG_PATH" -nobrowse -quiet | grep -E '^/dev/' | awk '{print $3}')
if [[ -z "$MOUNT_POINT" ]]; then
    echo "❌ 挂载失败"
    exit 1
fi

echo "🔍 查找应用..."
SOURCE_APP=$(find "$MOUNT_POINT" -name "IntelliJ IDEA*.app" -maxdepth 1 | head -1)
if [[ -z "$SOURCE_APP" ]]; then
    echo "❌ DMG 中未找到 IntelliJ IDEA 应用"
    hdiutil detach "$MOUNT_POINT" -quiet
    exit 1
fi

echo "📋 复制到 $INSTALL_DIR..."
if [[ -d "$INSTALL_DIR/$APP_NAME" ]]; then
    echo "⚠️  检测到已安装版本，备份..."
    mv "$INSTALL_DIR/$APP_NAME" "$INSTALL_DIR/${APP_NAME}.backup.$(date +%Y%m%d_%H%M%S)"
fi

cp -R "$SOURCE_APP" "$INSTALL_DIR/"

echo "🧹 卸载 DMG..."
hdiutil detach "$MOUNT_POINT" -quiet

echo "✅ 安装完成: $INSTALL_DIR/$APP_NAME"
echo ""
echo "验证安装:"
ls -la "$INSTALL_DIR/$APP_NAME"