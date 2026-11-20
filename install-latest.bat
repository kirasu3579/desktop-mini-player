@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

rem 一键安装最新版 spmod 到 Salt Player 的插件目录。
rem 用法：先完全退出 Salt Player，再双击本脚本。

set "ROOT=%~dp0"
set "SRC=%ROOT%build\libs\plugin-com.miniplayer.desktop-mini-player-1.1.0.spmod"
set "JAR=%ROOT%..\.tools\unz\jdk25.0.5_0\bin\jar.exe"
set "DEST=%APPDATA%\Salt Player for Windows\workshop\plugins\plugin-com.miniplayer.desktop-mini-player-1.1.0"

if not exist "%SRC%" (
    echo [错误] 找不到 %SRC%
    echo 请先在项目目录执行 bash build.sh
    pause
    exit /b 1
)
if not exist "%JAR%" (
    echo [错误] 找不到 JDK 的 jar.exe：%JAR%
    pause
    exit /b 1
)

tasklist /FI "IMAGENAME eq Salt Player for Windows.exe" 2>nul | find /I "Salt Player for Windows.exe" >nul
if not errorlevel 1 (
    echo [提示] Salt Player 正在运行，请先完全退出宿主，再重新运行本脚本。
    pause
    exit /b 1
)

echo [1/3] 准备目录
if exist "%DEST%" rmdir /s /q "%DEST%"
mkdir "%DEST%" 2>nul

echo [2/3] 解压 spmod
pushd "%DEST%"
"%JAR%" --extract --file "%SRC%"
if errorlevel 1 (
    echo [错误] 解压失败
    popd
    pause
    exit /b 1
)
popd

echo [3/3] 清理旧版本目录（同一插件只保留一份）
rem 用 plugin-com.miniplayer.* 匹配：既清掉本插件的历史版本，也清掉 1.1.0 之前那个旧插件 ID 的目录
for /d %%D in ("%APPDATA%\Salt Player for Windows\workshop\plugins\plugin-com.miniplayer.*") do (
    if /I not "%%~nxD"=="plugin-com.miniplayer.desktop-mini-player-1.1.0" (
        echo     删除 %%~nxD
        rmdir /s /q "%%D"
    )
)

echo.
echo 安装完成：%DEST%
echo.
echo 说明：显隐入口在 Salt Player 的插件设置面板里——
echo 「启用迷你播放器」这个开关改完立刻生效（关掉悬浮条马上消失，
echo 打开马上出现，不用重启播放器），面板里另有「显示 / 隐藏迷你条」按钮。
echo.
echo 本插件只需要 library-read 权限（读取播放信息与歌词）。
echo.
echo 现在启动 Salt Player，播放一首有逐字歌词的歌，看 15 秒以上。
pause
