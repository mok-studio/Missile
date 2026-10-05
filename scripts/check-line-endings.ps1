# ============================================================
#  仓库行尾自查（本仓库所有文本文件一律纯 LF）
#
#  为什么需要它：PowerShell 的 [System.IO.File]::WriteAllLines 按 Windows 习惯写 CRLF，
#  用它改 src/main/resources/**.yml 或 docs/**.md 会**静默**把整份文件变成 CRLF。
#  本会话已经踩过两次（msl_config.yml + 两份语言包 + 3 个 Java 文件 + 3 份文档），
#  第一次是被 dist/PersistCheck 的"逐字节保留"断言抓到才发现的。
#
#  用法：pwsh -File scripts/check-line-endings.ps1          # 只报告（有 CRLF 时退出码 1）
#        pwsh -File scripts/check-line-endings.ps1 -Fix    # 就地还原为 LF
# ============================================================
param(
    [switch]$Fix
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$extensions = @('.java', '.yml', '.md', '.ps1', '.txt', '.xml', '.gitignore')

function Get-Endings([string]$path) {
    $bytes = [System.IO.File]::ReadAllBytes($path)
    $crlf = 0
    $lf = 0
    for ($i = 0; $i -lt $bytes.Length; $i++) {
        if ($bytes[$i] -eq 10) {
            if ($i -gt 0 -and $bytes[$i - 1] -eq 13) { $crlf++ } else { $lf++ }
        }
    }
    return [pscustomobject]@{ Path = $path; CRLF = $crlf; LF = $lf }
}

$targets = New-Object System.Collections.Generic.List[string]
foreach ($dir in @('src', 'docs', 'scripts', '.mvn')) {
    $full = Join-Path $root $dir
    if (Test-Path $full) {
        Get-ChildItem $full -Recurse -File | Where-Object { $extensions -contains $_.Extension } |
            ForEach-Object { $targets.Add($_.FullName) }
    }
}
foreach ($name in @('pom.xml', '.gitignore')) {
    $full = Join-Path $root $name
    if (Test-Path $full) { $targets.Add($full) }
}

$bad = @()
foreach ($file in $targets) {
    $endings = Get-Endings $file
    if ($endings.CRLF -gt 0) { $bad += $endings }
}

if ($bad.Count -eq 0) {
    Write-Output ("行尾检查通过：{0} 个文本文件全部为纯 LF" -f $targets.Count)
    exit 0
}

foreach ($entry in $bad) {
    $relative = $entry.Path.Substring($root.Length + 1)
    if ($Fix) {
        $text = [System.IO.File]::ReadAllText($entry.Path)
        [System.IO.File]::WriteAllText($entry.Path, $text.Replace("`r`n", "`n"),
            (New-Object System.Text.UTF8Encoding($false)))
        Write-Output ("已还原为 LF: {0}（原有 {1} 处 CRLF）" -f $relative, $entry.CRLF)
    } else {
        Write-Output ("CRLF 混入: {0}（{1} 处）—— 用 -Fix 还原" -f $relative, $entry.CRLF)
    }
}

if (-not $Fix) {
    Write-Output ""
    Write-Output '提示：改这些文件请用编辑器工具，或用 WriteAllText 配 text.Replace(CRLF, LF)，不要用 WriteAllLines。'
    exit 1
}
exit 0
