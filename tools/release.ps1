# 把 build/libs 里的 spmod 发到 GitHub Release。
#
#   pwsh -File tools/release.ps1 -NotesPath docs/releases/v1.1.0.md
#   pwsh -File tools/release.ps1 -NotesPath notes.md -DryRun
#
# 版本号取自 VERSION.txt，tag 为 v<版本>，产物名 plugin-com.miniplayer.desktop-mini-player-<版本>.spmod。
# 令牌通过 `git credential fill` 取（Windows 的 Git Credential Manager 会提供已登录的 GitHub 令牌）。
# 已存在同名 release 时直接报错退出，不自动删除——发布过的版本不该被一次误跑悄悄替换。
param(
    [Parameter(Mandatory = $true)][string]$NotesPath,
    [string]$Repo = 'kirasu3579/desktop-mini-player',
    [string]$TargetCommitish = 'main',
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

$proj = Split-Path -Parent $PSScriptRoot
$ver = (Get-Content -Raw (Join-Path $proj 'VERSION.txt')).Trim()
$tag = "v$ver"
$assetName = "plugin-com.miniplayer.desktop-mini-player-$ver.spmod"
$asset = Join-Path $proj "build/libs/$assetName"

if (-not (Test-Path $asset)) { throw "找不到产物 $asset，先跑 bash build.sh" }
if (-not (Test-Path $NotesPath)) { throw "找不到发布说明 $NotesPath" }

$notes = [System.IO.File]::ReadAllText((Resolve-Path $NotesPath), [System.Text.Encoding]::UTF8)
$sha = (Get-FileHash $asset -Algorithm SHA256).Hash.ToLower()
$size = (Get-Item $asset).Length
"版本 = $ver"
"tag = $tag"
"产物 = $assetName ($size 字节)"
"SHA256 = $sha"

if ($DryRun) { "DryRun：不联网，到此为止"; return }

$cred = "protocol=https`nhost=github.com`n`n" | git credential fill 2>$null
$tok = (($cred | Select-String '^password=') -replace '^password=','').Trim()
if (-not $tok) { throw '取不到 GitHub 令牌；先在 Git Credential Manager 里登录一次' }
$h = @{ Authorization = "token $tok"; 'User-Agent' = 'dsh-agent'; Accept = 'application/vnd.github+json' }

try {
    $null = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/tags/$tag" -Headers $h -Method Get
    throw "release $tag 已存在。要重新发布请先手动删除：https://github.com/$Repo/releases"
} catch {
    if ($_.Exception.Message -like '*已存在*') { throw }
}

$body = @{
    tag_name         = $tag
    target_commitish = $TargetCommitish
    name             = "桌面迷你播放条 $ver"
    body             = $notes
    draft            = $true
    prerelease       = $false
} | ConvertTo-Json -Depth 5

$rel = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases" -Headers $h -Method Post `
        -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) -ContentType 'application/json; charset=utf-8'
"draft release id = $($rel.id)"

$uploadUrl = ($rel.upload_url -replace '\{\?.*\}', '') + '?name=' + [uri]::EscapeDataString($assetName)
$up = Invoke-RestMethod -Uri $uploadUrl -Headers $h -Method Post -InFile $asset -ContentType 'application/octet-stream'
"uploaded = $($up.name) size=$($up.size) state=$($up.state)"

$pub = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/$($rel.id)" -Headers $h -Method Patch `
        -Body ([System.Text.Encoding]::UTF8.GetBytes((@{ draft = $false } | ConvertTo-Json))) `
        -ContentType 'application/json; charset=utf-8'
"published = $($pub.html_url)"
