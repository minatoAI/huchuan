# Capture one screenshot per page of the Huchuan Windows shell.
# Driven by windows/scripts/ui-capture-jobs.json; called from scripts/build-windows.sh.
# Every path argument is an absolute Windows path.
#
# Each job: launch Huchuan.exe with its debug flags, wait until the shell logs the
# *settled* DOM probe for the expected page + visible copy, then grab the window.
# A screenshot is only accepted if the DOM text and the pixels agree.
#
# IMPORTANT: keep this file pure ASCII. PowerShell 5.1 decodes BOM-less scripts with the
# ANSI code page; non-ASCII literals would corrupt the following line.
param(
  [Parameter(Mandatory = $true)][string]$ExePath,
  [Parameter(Mandatory = $true)][string]$OutDir,
  [Parameter(Mandatory = $true)][string]$JobsPath,
  [int]$TimeoutSec = 40
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName System.Drawing
Add-Type -Namespace HuchuanCapture -Name Native -MemberDefinition @'
[DllImport("user32.dll", SetLastError = true)] public static extern bool SetProcessDpiAwarenessContext(IntPtr ctx);
[DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
[DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
[DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr hWnd, IntPtr insertAfter, int x, int y, int cx, int cy, uint flags);
[DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
[DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
[DllImport("user32.dll")] public static extern uint GetDpiForWindow(IntPtr hWnd);
[DllImport("dwmapi.dll")] public static extern int DwmGetWindowAttribute(IntPtr hWnd, int attr, out RECT rect, int size);
[StructLayout(LayoutKind.Sequential)]
public struct RECT { public int Left; public int Top; public int Right; public int Bottom; }
'@

# Physical pixels: without this the crop lands off the window on HiDPI screens.
$dpiAware = [HuchuanCapture.Native]::SetProcessDpiAwarenessContext([IntPtr]::new(-4))
if (-not $dpiAware) { throw 'SetProcessDpiAwarenessContext(PerMonitorV2) failed' }

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$logPath = Join-Path $env:LOCALAPPDATA 'Huchuan\logs\shell.log'

function Get-WindowBounds([IntPtr]$handle) {
  $rect = New-Object 'HuchuanCapture.Native+RECT'
  # DWMWA_EXTENDED_FRAME_BOUNDS (9) drops the invisible resize border so the crop hugs the window.
  if ([HuchuanCapture.Native]::DwmGetWindowAttribute($handle, 9, [ref]$rect, 16) -eq 0) { return $rect }
  if ([HuchuanCapture.Native]::GetWindowRect($handle, [ref]$rect)) { return $rect }
  throw 'cannot read window bounds'
}

function Measure-Shot([System.Drawing.Bitmap]$bitmap) {
  $colors = New-Object 'System.Collections.Generic.HashSet[int]'
  $luma = 0.0
  $count = 0
  $stepX = [Math]::Max(1, [int]($bitmap.Width / 220))
  $stepY = [Math]::Max(1, [int]($bitmap.Height / 160))
  for ($x = 0; $x -lt $bitmap.Width; $x += $stepX) {
    for ($y = 0; $y -lt $bitmap.Height; $y += $stepY) {
      $px = $bitmap.GetPixel($x, $y)
      [void]$colors.Add($px.ToArgb())
      $luma += 0.299 * $px.R + 0.587 * $px.G + 0.114 * $px.B
      $count++
    }
  }
  return @{ Colors = $colors.Count; Mean = [Math]::Round($luma / [Math]::Max(1, $count), 1) }
}

function Read-LogLines {
  try {
    if (-not (Test-Path $logPath)) { return @() }
    return @(Get-Content -Path $logPath -Encoding UTF8 -ErrorAction Stop)
  } catch {
    return @()
  }
}

$jobsFile = Get-Content -Path $JobsPath -Raw -Encoding UTF8 | ConvertFrom-Json
$jobs = @($jobsFile.jobs)
$results = @()
$failures = 0

foreach ($job in $jobs) {
  $name = [string]$job.name
  $shot = Join-Path $OutDir ($name + '.png')
  $logCopy = Join-Path $OutDir ($name + '.log')
  if (Test-Path $shot) { Remove-Item $shot -Force }
  if (Test-Path $logCopy) { Remove-Item $logCopy -Force }

  $record = [ordered]@{
    name       = $name
    describe   = [string]$job.describe
    route      = [string]$job.route
    theme      = if ($job.theme) { [string]$job.theme } else { 'light' }
    page       = [string]$job.page
    expect     = [string]$job.expect
    probeTag   = if ($job.probe) { [string]$job.probe } else { 'settled' }
    screenshot = $shot
    log        = $logCopy
    ok         = $false
    width      = 0
    height     = 0
    dpi        = 0
    colors     = 0
    luma       = 0
    probe      = $null
    error      = $null
  }

  $proc = $null
  try {
    $arguments = @()
    if ($job.route) { $arguments += ('--route=' + [string]$job.route) }
    if ($job.theme) { $arguments += ('--theme=' + [string]$job.theme) }
    if ($job.network) { $arguments += ('--network=' + [string]$job.network) }
    if ($job.hold) { $arguments += ('--hold=' + [string]$job.hold) }

    $launch = Get-Date
    $proc = Start-Process -FilePath $ExePath -ArgumentList $arguments -PassThru

    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $handle = [IntPtr]::Zero
    while ((Get-Date) -lt $deadline) {
      Start-Sleep -Milliseconds 150
      $proc.Refresh()
      if ($proc.HasExited) { throw "process exited early with code $($proc.ExitCode)" }
      if ($proc.MainWindowHandle -ne [IntPtr]::Zero -and [HuchuanCapture.Native]::IsWindowVisible($proc.MainWindowHandle)) {
        $handle = $proc.MainWindowHandle
        break
      }
    }
    if ($handle -eq [IntPtr]::Zero) { throw "no visible main window after $TimeoutSec s" }

    # Wait for the probe named by the job (settled = 1.5 s, late = 4 s after navigation):
    # page=<route> plus the copy that proves the right page rendered.
    $tag = if ($job.probe) { [string]$job.probe } else { 'settled' }
    $needle = 'page=' + [string]$job.page + '|'
    $probeLine = $null
    while ((Get-Date) -lt $deadline) {
      Start-Sleep -Milliseconds 150
      $proc.Refresh()
      if ($proc.HasExited) { throw "process exited early with code $($proc.ExitCode)" }
      $info = Get-Item -Path $logPath -ErrorAction SilentlyContinue
      if (-not $info -or $info.LastWriteTime -lt $launch) { continue }
      # Cast to [string]: Get-Content lines carry ETS properties (ReadCount) that would
      # otherwise serialize as an object in index.json.
      foreach ($line in (Read-LogLines)) {
        $text = [string]$line
        if ($text -like ('*' + $tag + ')*') -and $text -like ('*' + $needle + '*') -and $text -like ('*' + [string]$job.expect + '*')) {
          $probeLine = $text
        }
      }
      if ($probeLine) { break }
    }
    if (-not $probeLine) {
      throw ("no '" + $tag + "' DOM probe for " + $needle + " containing '" + [string]$job.expect + "' within $TimeoutSec s")
    }
    $record.probe = $probeLine

    [HuchuanCapture.Native]::ShowWindow($handle, 9) | Out-Null
    [HuchuanCapture.Native]::SetWindowPos($handle, [IntPtr]::new(-1), 0, 0, 0, 0, 0x43) | Out-Null
    $record.foreground = [HuchuanCapture.Native]::SetForegroundWindow($handle)
    $record.dpi = [HuchuanCapture.Native]::GetDpiForWindow($handle)
    Start-Sleep -Milliseconds 350

    $bounds = Get-WindowBounds $handle
    $width = $bounds.Right - $bounds.Left
    $height = $bounds.Bottom - $bounds.Top
    if ($width -le 0 -or $height -le 0) { throw 'window bounds are empty' }
    $record.width = $width
    $record.height = $height

    # WebView2 can paint one frame late: retry until the pixels match the theme.
    $wantDark = ($record.theme -eq 'dark')
    $attempt = 0
    $stats = $null
    while ($attempt -lt 8) {
      $attempt++
      $bitmap = New-Object System.Drawing.Bitmap($width, $height)
      $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
      $graphics.CopyFromScreen($bounds.Left, $bounds.Top, 0, 0, (New-Object System.Drawing.Size($width, $height)))
      $graphics.Dispose()
      $stats = Measure-Shot $bitmap
      $bitmap.Save($shot, [System.Drawing.Imaging.ImageFormat]::Png)
      $bitmap.Dispose()
      if ($stats.Colors -ge 32 -and (($wantDark -and $stats.Mean -le 120) -or (-not $wantDark -and $stats.Mean -ge 140))) { break }
      Start-Sleep -Milliseconds 300
    }
    $record.colors = $stats.Colors
    $record.luma = $stats.Mean
    [HuchuanCapture.Native]::SetWindowPos($handle, [IntPtr]::new(-2), 0, 0, 0, 0, 0x43) | Out-Null

    if ($stats.Colors -lt 32) { throw "screenshot has only $($stats.Colors) distinct colors; the page looks blank" }
    if ($wantDark -and $stats.Mean -gt 120) { throw "dark theme did not render (mean luminance $($stats.Mean))" }
    if (-not $wantDark -and $stats.Mean -lt 140) { throw "light theme did not render (mean luminance $($stats.Mean))" }

    $record.ok = $true
  } catch {
    $record.error = $_.Exception.Message
    $failures++
  } finally {
    if ($proc) {
      try {
        if (-not $proc.HasExited) {
          $proc.CloseMainWindow() | Out-Null
          Start-Sleep -Milliseconds 500
          $proc.Refresh()
          if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
        }
        $proc.WaitForExit(5000) | Out-Null
      } catch {
        # nothing to do: the next job starts a fresh process anyway
      }
    }
    if (Test-Path $logPath) {
      Copy-Item -Path $logPath -Destination $logCopy -Force -ErrorAction SilentlyContinue
    }
  }

  if ($record.ok) {
    Write-Output ('OK   ' + $name + ' ' + $record.width + 'x' + $record.height + ' colors=' + $record.colors + ' luma=' + $record.luma)
  } else {
    Write-Output ('FAIL ' + $name + ' : ' + $record.error)
  }
  $results += [pscustomobject]$record
}

$index = [ordered]@{
  ok        = ($failures -eq 0)
  exe       = $ExePath
  capturedAt = (Get-Date).ToString('yyyy-MM-ddTHH:mm:ssK')
  count     = $results.Count
  failed    = $failures
  shots     = $results
}
$json = $index | ConvertTo-Json -Depth 6
[System.IO.File]::WriteAllText((Join-Path $OutDir 'index.json'), $json, (New-Object System.Text.UTF8Encoding($false)))

Write-Output ('TOTAL ' + $results.Count + ' shots, ' + $failures + ' failed')
if ($failures -gt 0) { exit 1 }
exit 0
