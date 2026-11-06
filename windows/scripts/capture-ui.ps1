# Capture one screenshot per page of the Huchuan Windows shell.
# Driven by windows/scripts/ui-capture-jobs.json; called from scripts/build-windows.sh.
# Every path argument is an absolute Windows path.
#
# Two capture paths live here, and they are NOT equals:
#
#   offscreen (the gate)  Huchuan.exe --shot=<png> --shot-page=<page>
#                         The shell renders the page with ICoreWebView2CapturePreview and
#                         writes the PNG itself, then exits. The image comes out of the
#                         WebView2 compositor, so it does not care whether the desktop is
#                         rendering, whether the display is off, or whether the window is
#                         foreground / on top. A per-page run is: launch with --shot, wait
#                         for the process to exit, then check the DOM probe it logged and
#                         the pixels in the PNG it wrote.
#
#   GDI fallback (diagnostics only)  window screenshot via CopyFromScreen.
#                         Kept because it is the only way to see what is actually on the
#                         glass (window chrome, layering). It is not a gate: on a session
#                         with no rendering desktop it returns a solid color frame, so it
#                         can only ever ADD evidence, never pass or fail a page. It runs
#                         only when the offscreen path failed, lands in <name>.gdi.png,
#                         and the page stays failed.
#
# A screenshot is accepted only if the DOM probe (page name + visible copy) and the pixels
# (distinct colors + mean luminance) agree.
#
# IMPORTANT: keep this file pure ASCII. PowerShell 5.1 decodes BOM-less scripts with the
# ANSI code page; non-ASCII literals would corrupt the following line.
param(
  [Parameter(Mandatory = $true)][string]$ExePath,
  [Parameter(Mandatory = $true)][string]$OutDir,
  [Parameter(Mandatory = $true)][string]$JobsPath,
  [int]$TimeoutSec = 45,
  [int]$Attempts = 3,
  # Name of the per-run evidence index written into OutDir. Defaults to index.json (the
  # build gate). A second capture run into the same OutDir passes its own name so it does
  # not clobber the gate's index.
  [string]$IndexName = 'index.json',
  [switch]$NoGdiFallback
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

# Physical pixels: without this the GDI crop lands off the window on HiDPI screens.
# (The offscreen path does not need it: WebView2 renders at the process DPI by itself.)
$dpiAware = [HuchuanCapture.Native]::SetProcessDpiAwarenessContext([IntPtr]::new(-4))
if (-not $dpiAware) { throw 'SetProcessDpiAwarenessContext(PerMonitorV2) failed' }

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$logPath = Join-Path $env:LOCALAPPDATA 'Huchuan\logs\shell.log'

# Thresholds shared with main.cpp (--shot retries on the same numbers before it gives up).
# A frame that never rendered is a single flat color. A rendered page is never one: even the
# sparsest one here (P0 splash, P1 username) samples ~30 distinct colors, with the background
# legitimately covering 99.6% of the picture. So the floor stays low (8) and the dominant
# share is recorded as evidence only -- it must not be a gate.
$MinColors = 8
$LightMinLuma = 140.0
$DarkMaxLuma = 120.0

function Measure-Shot([System.Drawing.Bitmap]$bitmap) {
  $histogram = New-Object 'System.Collections.Generic.Dictionary[int,int]'
  $luma = 0.0
  $count = 0
  $stepX = [Math]::Max(1, [int]($bitmap.Width / 220))
  $stepY = [Math]::Max(1, [int]($bitmap.Height / 160))
  for ($x = 0; $x -lt $bitmap.Width; $x += $stepX) {
    for ($y = 0; $y -lt $bitmap.Height; $y += $stepY) {
      $px = $bitmap.GetPixel($x, $y)
      $argb = $px.ToArgb()
      if ($histogram.ContainsKey($argb)) { $histogram[$argb] = $histogram[$argb] + 1 } else { $histogram[$argb] = 1 }
      $luma += 0.299 * $px.R + 0.587 * $px.G + 0.114 * $px.B
      $count++
    }
  }
  $top = 0
  foreach ($value in $histogram.Values) { if ($value -gt $top) { $top = $value } }
  $dominant = [Math]::Round(100.0 * $top / [Math]::Max(1, $count), 1)
  return @{
    Colors   = $histogram.Count
    Mean     = [Math]::Round($luma / [Math]::Max(1, $count), 1)
    Dominant = $dominant
  }
}

function Measure-Png([string]$path) {
  $bitmap = New-Object System.Drawing.Bitmap($path)
  try {
    $stats = Measure-Shot $bitmap
    return @{
      Colors   = $stats.Colors
      Mean     = $stats.Mean
      Dominant = $stats.Dominant
      Width    = $bitmap.Width
      Height   = $bitmap.Height
    }
  } finally {
    $bitmap.Dispose()
  }
}

function Read-LogLines {
  try {
    if (-not (Test-Path $logPath)) { return @() }
    return @(Get-Content -Path $logPath -Encoding UTF8 -ErrorAction Stop)
  } catch {
    return @()
  }
}

function Get-ProbeLine([string]$tag, [string]$page, [string]$expect) {
  $needle = 'page=' + $page + '|'
  $found = $null
  foreach ($line in (Read-LogLines)) {
    $text = [string]$line    # Get-Content lines carry ETS properties; keep index.json plain
    if ($text -like ('*DOM*' + $tag + ')*') -and $text -like ('*' + $needle + '*') -and $text -like ('*' + $expect + '*')) {
      $found = $text
    }
  }
  return $found
}

function Test-Theme([string]$theme, [int]$colors, [double]$luma) {
  if ($colors -lt $MinColors) { return 'blank: only ' + $colors + ' distinct colors (nothing was drawn)' }
  if ($theme -eq 'dark') {
    if ($luma -gt $DarkMaxLuma) { return 'dark theme did not render (mean luminance ' + $luma + ')' }
  } elseif ($luma -lt $LightMinLuma) {
    return 'light theme did not render (mean luminance ' + $luma + ')'
  }
  return $null
}

function Start-Shell([string[]]$arguments) {
  # Start-Process joins ArgumentList with spaces and does not quote on its own (PS 5.1):
  # quote anything with a space in it, CommandLineToArgvW handles the rest.
  $quoted = @()
  foreach ($item in $arguments) {
    if ($item -match ' ') { $quoted += ('"' + $item + '"') } else { $quoted += $item }
  }
  return Start-Process -FilePath $ExePath -ArgumentList $quoted -PassThru
}

function Stop-Shell($proc) {
  if (-not $proc) { return }
  try {
    $proc.Refresh()
    if (-not $proc.HasExited) {
      if (-not $proc.WaitForExit(8000)) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
    }
  } catch {
    # the next job starts a fresh process anyway
  }
}

# ---------------------------------------------------------------- offscreen path
function Invoke-OffscreenShot($job, [string]$shot, [int]$delayMs, [int]$pageTimeoutMs) {
  $record = @{
    ok = $false; error = $null; probe = $null; exitCode = $null
    width = 0; height = 0; colors = 0; dominant = 0; luma = 0
  }
  $arguments = @()
  if ($job.daemonPort) { $arguments += ('--daemon-port=' + [string]$job.daemonPort) }
  if ($job.route) { $arguments += ('--route=' + [string]$job.route) }
  if ($job.theme) { $arguments += ('--theme=' + [string]$job.theme) }
  if ($job.network) { $arguments += ('--network=' + [string]$job.network) }
  if ($job.hold) { $arguments += ('--hold=' + [string]$job.hold) }
  $arguments += ('--shot=' + $shot)
  $arguments += ('--shot-page=' + [string]$job.page)
  $arguments += ('--shot-delay=' + $delayMs)
  $arguments += ('--shot-timeout=' + $pageTimeoutMs)

  $proc = Start-Shell $arguments
  if (-not $proc.WaitForExit($TimeoutSec * 1000)) {
    Stop-Shell $proc
    $record.error = 'shell did not exit within ' + $TimeoutSec + ' s (offscreen capture hung)'
    return $record
  }
  $record.exitCode = $proc.ExitCode

  if (-not (Test-Path $shot)) {
    $record.error = 'no PNG was written (exit code ' + $proc.ExitCode + ')'
    return $record
  }
  $png = Get-Item -Path $shot
  if ($png.Length -lt 1000) {
    $record.error = 'PNG is only ' + $png.Length + ' bytes (exit code ' + $proc.ExitCode + ')'
    return $record
  }

  $probe = Get-ProbeLine 'shot' ([string]$job.page) ([string]$job.expect)
  if (-not $probe) {
    $record.error = ('no shot DOM probe for page=' + $job.page + ' containing "' + [string]$job.expect +
                     '" (the page that got captured was not the expected one)')
    return $record
  }
  $record.probe = $probe

  $stats = Measure-Png $shot
  $record.width = $stats.Width
  $record.height = $stats.Height
  $record.colors = $stats.Colors
  $record.dominant = $stats.Dominant
  $record.luma = $stats.Mean
  $theme = if ($job.theme) { [string]$job.theme } else { 'light' }
  $problem = Test-Theme $theme $stats.Colors $stats.Mean
  if ($problem) { $record.error = $problem; return $record }

  if ($proc.ExitCode -ne 0) {
    $record.error = 'shell exited with ' + $proc.ExitCode + ' although the PNG looks right'
    return $record
  }
  $record.ok = $true
  return $record
}

# ------------------------------------------------------------ GDI fallback (not a gate)
function Invoke-GdiShot($job, [string]$shot) {
  $record = @{ ok = $false; error = $null; width = 0; height = 0; colors = 0; dominant = 0; luma = 0 }
  $proc = $null
  try {
    $arguments = @()
    if ($job.daemonPort) { $arguments += ('--daemon-port=' + [string]$job.daemonPort) }
    if ($job.route) { $arguments += ('--route=' + [string]$job.route) }
    if ($job.theme) { $arguments += ('--theme=' + [string]$job.theme) }
    if ($job.network) { $arguments += ('--network=' + [string]$job.network) }
    if ($job.hold) { $arguments += ('--hold=' + [string]$job.hold) }
    $proc = Start-Shell $arguments

    # Same wait as before: the DOM probe for this job, then a moment for WebView2 to paint.
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $probe = $null
    while ((Get-Date) -lt $deadline) {
      Start-Sleep -Milliseconds 150
      $proc.Refresh()
      if ($proc.HasExited) { throw ('process exited early with code ' + $proc.ExitCode) }
      $probe = Get-ProbeLine 'settled' ([string]$job.page) ([string]$job.expect)
      if ($probe) { break }
    }
    if (-not $probe) { throw ('no settled DOM probe for page=' + $job.page) }

    $handle = $proc.MainWindowHandle
    if ($handle -eq [IntPtr]::Zero -or -not [HuchuanCapture.Native]::IsWindowVisible($handle)) {
      throw 'no visible main window'
    }
    [HuchuanCapture.Native]::SetForegroundWindow($handle) | Out-Null
    $record.foreground = $true
    Start-Sleep -Milliseconds 350

    $bounds = New-Object 'HuchuanCapture.Native+RECT'
    if ([HuchuanCapture.Native]::DwmGetWindowAttribute($handle, 9, [ref]$bounds, 16) -ne 0) {
      if (-not [HuchuanCapture.Native]::GetWindowRect($handle, [ref]$bounds)) { throw 'cannot read window bounds' }
    }
    $width = $bounds.Right - $bounds.Left
    $height = $bounds.Bottom - $bounds.Top
    if ($width -le 0 -or $height -le 0) { throw 'window bounds are empty' }

    $bitmap = New-Object System.Drawing.Bitmap($width, $height)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.CopyFromScreen($bounds.Left, $bounds.Top, 0, 0, (New-Object System.Drawing.Size($width, $height)))
    $graphics.Dispose()
    $stats = Measure-Shot $bitmap
    $bitmap.Save($shot, [System.Drawing.Imaging.ImageFormat]::Png)
    $bitmap.Dispose()
    $record.width = $width
    $record.height = $height
    $record.colors = $stats.Colors
    $record.dominant = $stats.Dominant
    $record.luma = $stats.Mean
    $record.ok = $true
  } catch {
    $record.error = $_.Exception.Message
  } finally {
    Stop-Shell $proc
  }
  return $record
}

# ------------------------------------------------------------------------ main loop
$jobsFile = Get-Content -Path $JobsPath -Raw -Encoding UTF8 | ConvertFrom-Json
$jobs = @($jobsFile.jobs)
$results = @()
$failures = 0

foreach ($job in $jobs) {
  $name = [string]$job.name
  $shot = Join-Path $OutDir ($name + '.png')
  $gdiShot = Join-Path $OutDir ($name + '.gdi.png')
  $logCopy = Join-Path $OutDir ($name + '.log')
  $gdiLog = Join-Path $OutDir ($name + '.gdi.log')
  foreach ($stale in @($shot, $gdiShot, $logCopy, $gdiLog)) {
    if (Test-Path $stale) { Remove-Item $stale -Force }
  }

  $delayMs = if ($job.shotDelayMs) { [int]$job.shotDelayMs } else { 2200 }
  $pageTimeoutMs = if ($job.shotTimeoutMs) { [int]$job.shotTimeoutMs } else { 15000 }

  $record = [ordered]@{
    name       = $name
    describe   = [string]$job.describe
    route      = [string]$job.route
    theme      = if ($job.theme) { [string]$job.theme } else { 'light' }
    page       = [string]$job.page
    expect     = [string]$job.expect
    mode       = 'offscreen'
    screenshot = $shot
    log        = $logCopy
    ok         = $false
    attempts   = 0
    width      = 0
    height     = 0
    colors     = 0
    dominant   = 0
    luma       = 0
    exitCode   = $null
    probe      = $null
    error      = $null
    gdi        = $null
  }

  $last = $null
  for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
    $record.attempts = $attempt
    if (Test-Path $shot) { Remove-Item $shot -Force }
    $last = Invoke-OffscreenShot $job $shot $delayMs $pageTimeoutMs
    if (Test-Path $logPath) {
      Copy-Item -Path $logPath -Destination $logCopy -Force -ErrorAction SilentlyContinue
    }
    $record.exitCode = $last.exitCode
    $record.probe = $last.probe
    if ($last.ok) { break }
    Start-Sleep -Milliseconds 250
  }

  $record.width = $last.width
  $record.height = $last.height
  $record.colors = $last.colors
  $record.dominant = $last.dominant
  $record.luma = $last.luma
  $record.ok = [bool]$last.ok
  if (-not $record.ok) {
    $record.error = [string]$last.error
    $failures++
    if (-not $NoGdiFallback) {
      # Diagnostics only: on a non-rendering desktop this is a solid color frame, and even
      # when it is not, it never flips the page to ok. Look at it, do not gate on it.
      $gdi = Invoke-GdiShot $job $gdiShot
      # The fallback launches its own shell, which truncates shell.log: its log goes to its
      # own file so the offscreen run's log (the evidence for this page) is never clobbered.
      if (Test-Path $logPath) {
        Copy-Item -Path $logPath -Destination $gdiLog -Force -ErrorAction SilentlyContinue
      }
      $record.gdi = [pscustomobject]@{
        screenshot = $gdiShot
        log        = $gdiLog
        ok         = [bool]$gdi.ok
        width      = $gdi.width
        height     = $gdi.height
        colors     = $gdi.colors
        dominant   = $gdi.dominant
        luma       = $gdi.luma
        error      = $gdi.error
        note       = 'fallback, not a gate'
      }
      if (-not $gdi.ok -and (Test-Path $gdiShot)) { Remove-Item $gdiShot -Force }
    }
  }

  if ($record.ok) {
    Write-Output ('OK   ' + $name + ' ' + $record.width + 'x' + $record.height + ' colors=' + $record.colors + ' dominant=' + $record.dominant + '% luma=' + $record.luma + ' attempt=' + $record.attempts)
  } else {
    Write-Output ('FAIL ' + $name + ' : ' + $record.error)
    if ($record.gdi) {
      Write-Output ('     gdi fallback (not a gate): colors=' + $record.gdi.colors + ' dominant=' + $record.gdi.dominant + '% luma=' + $record.gdi.luma + ' ok=' + $record.gdi.ok)
    }
  }
  $results += [pscustomobject]$record
}

$index = [ordered]@{
  ok         = ($failures -eq 0)
  mode       = 'offscreen'
  exe        = $ExePath
  capturedAt = (Get-Date).ToString('yyyy-MM-ddTHH:mm:ssK')
  count      = $results.Count
  failed     = $failures
  shots      = $results
}
$json = $index | ConvertTo-Json -Depth 6
[System.IO.File]::WriteAllText((Join-Path $OutDir $IndexName), $json, (New-Object System.Text.UTF8Encoding($false)))

Write-Output ('TOTAL ' + $results.Count + ' shots, ' + $failures + ' failed')
if ($failures -gt 0) { exit 1 }
exit 0
