<#
用本机 JDK 直接编译模组源码（替代跑不起来的 Gradle）。

沙箱里 Gradle 的 native-platform 加载失败，所以这里直接调 javac，
classpath 由 NeoForm 的 recompiled jar + NeoForge + client-extra + Gradle 依赖缓存拼成。

用法：
    pwsh -File tools\compile.ps1

产物：build\classes\**\*.class（之后用 tools\package.py 打包）
#>

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$jdk  = 'C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.3\jbr\bin'
$javac = Join-Path $jdk 'javac.exe'
if (-not (Test-Path $javac)) {
    throw "找不到 javac：$javac（改脚本里的 `$jdk）"
}

# javac 处理不了中文路径的 argfile，所以全部换成相对路径 + 正斜杠
# NeoForm 重编译产物和 Gradle 依赖缓存有几百 MB，本工程不复制它们。
# 本地没有就回落到同级目录的同名工程（本工程由它派生而来）。
$sibling = Join-Path (Split-Path -Parent $root) 'enderportal'
$deps = @(
    'build/neoForm/neoFormJoined1.21.1-20240808.144430/steps/packRecomp/output.jar',
    '.gradle/repositories/ng_dummy_ng/net/neoforged/neoforge/21.1.72/neoforge-21.1.72.jar',
    '.gradle/repositories/ng_dummy_ng/net/minecraft/client/1.21.1/client-1.21.1-client-extra.jar'
)
$cp = @()
foreach ($dep in $deps) {
    if (Test-Path (Join-Path $root $dep)) {
        $cp += $dep
        Write-Host "依赖（本工程）：$((Join-Path $root $dep))"
    } elseif (Test-Path (Join-Path $sibling $dep)) {
        $cp += "../enderportal/$dep"
        # 回落只保证"文件存在"，所以把真正命中的绝对路径打出来，方便核对是不是同一个版本
        Write-Host "依赖（回落 enderportal）：$((Join-Path $sibling $dep))"
    } else {
        throw "找不到依赖：$dep（本地和 $sibling 都没有）"
    }
}
$cache = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'
if (Test-Path $cache) {
    # 缓存里同一个库常有多份（universal / userdev / 普通 jar），Get-ChildItem 的返回顺序不保证稳定，
    # 所以按完整路径排序，保证每次编译拿到的 classpath 完全一致
    $cached = @(Get-ChildItem $cache -Recurse -Filter *.jar |
                Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } |
                Sort-Object FullName |
                ForEach-Object { $_.FullName.Replace('\', '/') })
    Write-Host "Gradle 缓存：$($cached.Count) 个 jar（已按路径排序）"
    if ($cached.Count -gt 0) { $cp += $cached }
}

# 这次真正用到的依赖一行一个落盘，编译出怪问题时直接对照，不用猜顺序
$cpDump = Join-Path $root 'build\javac-classpath.txt'
try {
    New-Item -ItemType Directory -Path (Join-Path $root 'build') -Force | Out-Null
    Set-Content -Path $cpDump -Value $cp -Encoding UTF8
    Write-Host "classpath 清单：$cpDump（共 $($cp.Count) 条）"
} catch {
    Write-Host "classpath 清单写入失败：$($_.Exception.Message)"
}

$sources = Get-ChildItem (Join-Path $root 'src\main\java') -Recurse -Filter *.java |
           ForEach-Object { $_.FullName.Substring($root.Length + 1).Replace('\', '/') }

$classes = Join-Path $root 'build\classes'
if (Test-Path $classes) { Remove-Item $classes -Recurse -Force }
New-Item -ItemType Directory -Path $classes -Force | Out-Null

$cpString = '"' + ($cp -join ';') + '"'
$arguments = @(
    '-d', 'build/classes',
    '--release', '21',
    '-encoding', 'UTF-8',
    '-proc:none',
    '-Xlint:all',
    '-classpath', $cpString
) + $sources

$argFile = Join-Path $env:TEMP 'smalltools-javac.txt'
Set-Content -Path $argFile -Value $arguments -Encoding ASCII

Push-Location $root
try {
    & $javac "@$argFile"
    if ($LASTEXITCODE -ne 0) { throw "javac 失败（exit $LASTEXITCODE）" }
    $count = (Get-ChildItem $classes -Recurse -Filter *.class | Measure-Object).Count
    Write-Host "编译完成：$count 个 class -> build\classes"
}
finally {
    Pop-Location
}
