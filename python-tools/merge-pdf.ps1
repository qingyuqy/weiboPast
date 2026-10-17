<#
.SYNOPSIS
  把“微博备份导出”生成的多个 part HTML 合并为一个 PDF。
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\merge-pdf.ps1
  # 自动扫描 C:\Users\QingYu\Downloads 下的 weibo-*.html（含 -part-N 分文件），合并成 weibo-merged-<时间>.pdf
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\merge-pdf.ps1 -Path D:\exports -Pattern 'weibo-*.html' -OutPdf D:\exports\我的微博.pdf
#>
param(
  [string]$Path = (Join-Path $env:USERPROFILE 'Downloads'),
  [string]$Pattern = 'weibo-*.html',
  [string]$OutPdf = '',
  [switch]$KeepTemp
)
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

# ---- 定位 Edge ----
$edge = $null
$candidates = @(
  "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe",
  "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe",
  "$env:LOCALAPPDATA\Microsoft\Edge\Application\msedge.exe"
)
foreach ($c in $candidates) { if (Test-Path $c) { $edge = $c; break } }
if (-not $edge -and (Get-Command msedge -ErrorAction SilentlyContinue)) { $edge = (Get-Command msedge -ErrorAction SilentlyContinue).Source }
if (-not $edge) { Write-Host '未找到 Edge，请修改脚本中的 Edge 路径。'; exit 1 }

# ---- 查找 HTML 文件 ----
$files = @(Get-ChildItem -LiteralPath $Path -Filter $Pattern -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -match '\.html$' } | Sort-Object Name)
if (-not $files) { Write-Host "在 $Path 下未找到 $Pattern 文件。"; exit 1 }
Write-Host "找到 $($files.Count) 个 HTML 文件，开始逐个打印为 PDF（无头 Edge，请稍候）..."

$tempDir = Join-Path $env:TEMP ('wbx-merge-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tempDir | Out-Null
$profileDir = Join-Path $tempDir 'profile'
$pdfs = @()

try {
  $n = 0
  foreach ($f in $files) {
    $n++
    $pdf = Join-Path $tempDir ($f.BaseName + '.pdf')
    $uri = [System.Uri]::new($f.FullName).AbsoluteUri
    Write-Host ("  [{0}/{1}] {2}" -f $n, $files.Count, $f.Name)
    $edgeArgs = @('--headless=new','--disable-gpu','--no-first-run',"--user-data-dir=$profileDir",'--no-pdf-header-footer',"--print-to-pdf=$pdf",'--virtual-time-budget=15000',$uri)
    $proc = Start-Process -FilePath $edge -ArgumentList $edgeArgs -Wait -PassThru -WindowStyle Hidden
    Start-Sleep -Milliseconds 500
    if (-not (Test-Path $pdf) -or (Get-Item $pdf).Length -lt 1024) {
      Write-Host "  警告：$($f.Name) 打印失败或为空，已跳过"
      continue
    }
    $pdfs += $pdf
  }
  if (-not $pdfs) { Write-Host '没有成功生成任何 PDF，退出。'; exit 1 }

  if (-not $OutPdf) { $OutPdf = Join-Path $Path ('weibo-merged-' + (Get-Date -Format 'yyyyMMdd-HHmm') + '.pdf') }

  $helper = Join-Path $scriptDir 'merge-pdf-helper.py'
  $argList = @($helper, $OutPdf) + $pdfs
  $pyOut = & python $argList 2>&1
  $pyOut | ForEach-Object { Write-Host "  $_" }
  if (-not (Test-Path $OutPdf)) { Write-Host '合并失败：未生成输出 PDF。'; exit 1 }
  $sizeMb = [math]::Round((Get-Item $OutPdf).Length / 1MB, 1)
  Write-Host "完成：$OutPdf（$sizeMb MB）"
} finally {
  if (-not $KeepTemp) {
    Remove-Item -LiteralPath $tempDir -Recurse -Force -ErrorAction SilentlyContinue
  }
}
