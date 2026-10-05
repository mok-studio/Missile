<#
    Missile → GitHub 一键发布脚本

    用法（在本机**普通终端**里运行，别在受限沙箱里跑，沙箱里没有 git 且 github 不通）：
        pwsh -NoProfile -File scripts/publish-to-github.ps1
        pwsh -NoProfile -File scripts/publish-to-github.ps1 -Remote https://github.com/你的名字/你的仓库.git

    前提：
        1) 已安装 Git for Windows（https://git-scm.com/download/win）
        2) 已能向该仓库推送（HTTPS 会弹凭据管理器 / 用 PAT；或已配好 SSH）

    安全约定：
        · 绝不 force push。远端已有提交时停止并提示你先 pull。
        · 只写仓库内的 .git/config，不动你的全局 gitconfig。
        · 提交前检查 .m2repo/ 与 target/ 是否被误纳入，是则中止。
#>
param(
    [string]$Remote = 'https://github.com/mok-studio/missile.git',
    [string]$Branch = 'main',
    [string]$UserName = 'mok-studio',
    [string]$UserEmail = 'mok-studio@users.noreply.github.com',
    [string]$Message = 'Missile 1.0.2: Paper 1.21.11 TNT 制导导弹插件（五种型号 + RWR + 目标筛选 + 多语言）'
)

$ErrorActionPreference = 'Stop'

function Fail([string]$text) { Write-Host "[x] $text" -ForegroundColor Red; exit 1 }
function Info([string]$text) { Write-Host "[*] $text" -ForegroundColor Cyan }
function Ok([string]$text)   { Write-Host "[+] $text" -ForegroundColor Green }

# 1. 工具检查
if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    Fail "找不到 git。请先安装 Git for Windows 后重开终端：https://git-scm.com/download/win"
}
$git = (Get-Command git).Source
Info "使用 git: $git"

# 2. 定位仓库根（本脚本在 scripts/ 下）
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
if (-not (Test-Path (Join-Path $root 'pom.xml'))) { Fail "在 $root 没找到 pom.xml，脚本位置不对？" }
Info "仓库根目录: $root"

# 3. 初始化
if (-not (Test-Path (Join-Path $root '.git'))) {
    Info "初始化本地仓库（分支 $Branch）"
    & git init -b $Branch 2>$null
    if ($LASTEXITCODE -ne 0) { & git init; & git symbolic-ref HEAD "refs/heads/$Branch" }
} else {
    Info "已有 .git，跳过初始化"
}

# 4. 只在仓库内设置身份（不依赖全局 gitconfig）
& git config user.name  $UserName
& git config user.email $UserEmail

# 5. 暂存并做安全检查
& git add -A
$staged = & git diff --cached --name-only
if ($staged -match '^\.m2repo/') { Fail ".m2repo/ 被误纳入！请确认 .gitignore 存在后重试（git reset 可撤销暂存）" }
if ($staged -match '^target/')   { Fail "target/ 被误纳入！请确认 .gitignore 存在后重试" }
Ok ("暂存文件数: " + @($staged).Count)

# 6. 提交（无改动则跳过）
& git rev-parse --verify HEAD *> $null
$hasCommit = ($LASTEXITCODE -eq 0)
if (@($staged).Count -gt 0) {
    & git commit -m $Message
    if ($LASTEXITCODE -ne 0) { Fail "git commit 失败" }
    Ok "已提交"
} elseif (-not $hasCommit) {
    & git commit --allow-empty -m "chore: 初始化仓库"
    Ok "空仓库初始提交"
} else {
    Info "没有新的改动，跳过提交"
}

# 7. 远端
$existing = (& git remote) -contains 'origin'
if ($existing) {
    & git remote set-url origin $Remote
} else {
    & git remote add origin $Remote
}
Ok "远端: $Remote"

# 8. 连通性 / 权限预检（失败时给出明确指引，而不是让 push 卡住）
Info "预检远端访问权限（可能需要登录）…"
& git ls-remote --heads origin
if ($LASTEXITCODE -ne 0) {
    Write-Host @"
[x] 无法访问远端。常见原因与处理：
    · 仓库不存在 / 路径拼错 → 先在 GitHub 网页上创建空仓库（不要勾选 README）
    · 需要登录 → HTTPS 建议用 Personal Access Token（勾 repo 权限）：
        git config --local credential.helper manager
      然后重跑本脚本，按提示输入用户名 + Token 作为密码
    · 想用 SSH → git remote set-url origin git@github.com:mok-studio/missile.git
"@ -ForegroundColor Red
    exit 1
}

# 9. 远端非空则停止（绝不 force）
$remoteHeads = (& git ls-remote --heads origin) -join "`n"
if ($remoteHeads.Trim().Length -gt 0) {
    Write-Host "[!] 远端已有分支/提交：" -ForegroundColor Yellow
    Write-Host $remoteHeads
    Write-Host @"
    为避免覆盖别人的提交，脚本在这里停下。请二选一后重跑：
      A) 合并远端历史： git fetch origin ; git rebase origin/$Branch
      B) 确认远端内容可丢弃： git push --force-with-lease -u origin $Branch   （请自行确认后再执行）
"@ -ForegroundColor Yellow
    exit 2
}

# 10. 推送
Info "推送到 origin/$Branch …"
& git push -u origin $Branch
if ($LASTEXITCODE -ne 0) { Fail "push 失败（若是认证问题，见上面第 8 步的指引）" }
Ok "完成：$Remote"
