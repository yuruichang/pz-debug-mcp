param()
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$bitmap = [Drawing.Bitmap]::new(256, 256)
$graphics = [Drawing.Graphics]::FromImage($bitmap)
$background = [Drawing.ColorTranslator]::FromHtml('#0d1822')
$teal = [Drawing.SolidBrush]::new([Drawing.ColorTranslator]::FromHtml('#5de2b8'))
$white = [Drawing.SolidBrush]::new([Drawing.ColorTranslator]::FromHtml('#edf5f6'))
$muted = [Drawing.SolidBrush]::new([Drawing.ColorTranslator]::FromHtml('#9fb5c2'))
$wire = [Drawing.Pen]::new($teal, 4)
$border = [Drawing.Pen]::new([Drawing.ColorTranslator]::FromHtml('#294455'), 2)
$label = [Drawing.Font]::new('Segoe UI', 16, [Drawing.FontStyle]::Bold)
$title = [Drawing.Font]::new('Segoe UI', 28, [Drawing.FontStyle]::Bold)
$small = [Drawing.Font]::new('Segoe UI', 10, [Drawing.FontStyle]::Regular)
$code = [Drawing.Font]::new('Consolas', 26, [Drawing.FontStyle]::Bold)
$align = [Drawing.StringFormat]::new()
$align.Alignment = [Drawing.StringAlignment]::Center
try {
    $graphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.TextRenderingHint = [Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    $graphics.Clear($background)
    $graphics.DrawRectangle($border, 14, 14, 228, 228)
    $graphics.DrawString('PZ DEBUG', $label, $white, [Drawing.RectangleF]::new(0, 28, 256, 26), $align)
    $graphics.DrawRectangle($wire, 87, 77, 82, 65)
    $graphics.DrawString('{ }', $code, $white, [Drawing.RectangleF]::new(87, 84, 82, 50), $align)
    $graphics.DrawLine($wire, 49, 109, 87, 109)
    $graphics.DrawLine($wire, 169, 93, 202, 93)
    $graphics.DrawLine($wire, 169, 125, 202, 125)
    foreach ($node in @(@(39, 100), @(200, 84), @(200, 116))) {
        $graphics.FillEllipse($teal, $node[0], $node[1], 18, 18)
    }
    $graphics.DrawString('MCP', $title, $teal, [Drawing.RectangleF]::new(0, 155, 256, 52), $align)
    $graphics.DrawString('JAVA DEBUG BRIDGE', $small, $muted, [Drawing.RectangleF]::new(0, 205, 256, 25), $align)
    $bitmap.Save((Join-Path $root 'preview.png'), [Drawing.Imaging.ImageFormat]::Png)
    foreach ($folder in @('common', '42')) {
        $destination = Join-Path $root "Contents/mods/PZDebugMCP/$folder"
        New-Item -ItemType Directory -Path $destination -Force | Out-Null
        $bitmap.Save((Join-Path $destination 'poster.png'), [Drawing.Imaging.ImageFormat]::Png)
        $icon = [Drawing.Bitmap]::new($bitmap, 128, 128)
        try { $icon.Save((Join-Path $destination 'icon.png'), [Drawing.Imaging.ImageFormat]::Png) }
        finally { $icon.Dispose() }
    }
} finally {
    foreach ($resource in @($align, $code, $small, $title, $label, $border, $wire, $muted, $white, $teal, $graphics, $bitmap)) { $resource.Dispose() }
}
