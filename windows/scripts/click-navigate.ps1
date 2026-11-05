# Click-through smoke test for the Huchuan Windows shell.
#
# Optional: not part of the build gate. scripts/build-windows.sh proves every page renders;
# this script proves the click path between pages still works after a UI change.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File windows/scripts/click-navigate.ps1 ^
#       -ExePath <abs path to Huchuan.exe> -OutDir <abs output dir>
#
# Coordinates are DIP (device independent pixels) and scaled by the window DPI, so the
# script works at 100% / 150% / 200% scaling. ASCII only: PowerShell 5.1 decodes BOM-less
# scripts with the ANSI code page.
param(
  [Parameter(Mandatory = $true)][string]$ExePath,
  [Parameter(Mandatory = $true)][string]$OutDir
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName System.Drawing
Add-Type -Namespace HuchuanClick -Name Native -MemberDefinition @'
[DllImport("user32.dll", SetLastError = true)] public static extern bool SetProcessDpiAwarenessContext(IntPtr ctx);
[DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
[DllImport("user32.dll")] public static extern void mouse_event(uint flags, uint dx, uint dy, uint data, IntPtr extra);
[DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
[DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr hWnd, IntPtr after, int x, int y, int cx, int cy, uint flags);
[DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int cmd);
[DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
[DllImport("user32.dll")] public static extern uint GetDpiForWindow(IntPtr hWnd);
[StructLayout(LayoutKind.Sequential)]
public struct RECT { public int Left; public int Top; public int Right; public int Bottom; }
'@

[void][HuchuanClick.Native]::SetProcessDpiAwarenessContext([IntPtr]::new(-4))
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$proc = Start-Process -FilePath $ExePath -ArgumentList @('--route=p2', '--theme=light') -PassThru
Start-Sleep -Seconds 3
$proc.Refresh()
$handle = $proc.MainWindowHandle
if ($handle -eq [IntPtr]::Zero) { throw 'no main window' }

[void][HuchuanClick.Native]::ShowWindow($handle, 9)
[void][HuchuanClick.Native]::SetWindowPos($handle, [IntPtr]::new(-1), 0, 0, 0, 0, 0x43)
[void][HuchuanClick.Native]::SetForegroundWindow($handle)
Start-Sleep -Milliseconds 700

$scale = [HuchuanClick.Native]::GetDpiForWindow($handle) / 96.0
Write-Output ('DPI scale ' + $scale)

function Get-Bounds {
  $rect = New-Object 'HuchuanClick.Native+RECT'
  [void][HuchuanClick.Native]::GetWindowRect($handle, [ref]$rect)
  return $rect
}

function Save-Shot([string]$name) {
  $rect = Get-Bounds
  $width = $rect.Right - $rect.Left
  $height = $rect.Bottom - $rect.Top
  $bitmap = New-Object System.Drawing.Bitmap($width, $height)
  $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
  $graphics.CopyFromScreen($rect.Left, $rect.Top, 0, 0, (New-Object System.Drawing.Size($width, $height)))
  $graphics.Dispose()
  $bitmap.Save((Join-Path $OutDir ($name + '.png')), [System.Drawing.Imaging.ImageFormat]::Png)
  $bitmap.Dispose()
  Write-Output ('SHOT ' + $name)
}

function Click-Dip([int]$x, [int]$y, [string]$name) {
  $rect = Get-Bounds
  [void][HuchuanClick.Native]::SetCursorPos($rect.Left + [int]($x * $scale), $rect.Top + [int]($y * $scale))
  Start-Sleep -Milliseconds 120
  [HuchuanClick.Native]::mouse_event(0x0002, 0, 0, 0, [IntPtr]::Zero)
  [HuchuanClick.Native]::mouse_event(0x0004, 0, 0, 0, [IntPtr]::Zero)
  Start-Sleep -Milliseconds 500
  Save-Shot $name
}

try {
  Save-Shot 'nav-0-p2'
  Click-Dip 200 142 'nav-1-p3'      # P2 group row
  Click-Dip 200 189 'nav-2-p4'      # P3 first file row
  Click-Dip 42 55 'nav-3-back-p3'   # header back arrow
  Click-Dip 980 55 'nav-4-p6'       # P3 members entry
  Click-Dip 42 55 'nav-5-back-p3'
  Click-Dip 42 55 'nav-6-back-p2'
  Click-Dip 980 55 'nav-7-p7'       # P2 settings entry
  Write-Output 'DONE'
} finally {
  $proc.CloseMainWindow() | Out-Null
  Start-Sleep -Milliseconds 500
  $proc.Refresh()
  if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
}
