#requires -Version 7.0
param(
    [Parameter(Mandatory = $true)][string]$Source,
    [Parameter(Mandatory = $true)][string]$PreviewDirectory
)

# Local, dependency-free conversion of the approved artwork; never generates new art.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$references = @('System.Drawing.Common', 'System.Drawing.Primitives', 'System.Collections', 'System.Console')
# .NET 10 splits GDI+ interop into two additional assemblies.
foreach ($assembly in @('System.Private.Windows.GdiPlus', 'System.Private.Windows.Core')) {
    if (Test-Path (Join-Path $PSHOME "$assembly.dll")) { $references += $assembly }
}
Add-Type -ReferencedAssemblies $references -TypeDefinition @'
using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Collections.Generic;
using System.IO;

public static class FarmLauncherIcon {
    static readonly Color Ivory = Color.FromArgb(252, 244, 227);
    static Graphics Canvas(Bitmap b) {
        var g = Graphics.FromImage(b);
        g.CompositingQuality = CompositingQuality.HighQuality;
        g.InterpolationMode = InterpolationMode.HighQualityBicubic;
        g.PixelOffsetMode = PixelOffsetMode.HighQuality;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        return g;
    }
    static Bitmap Render(Bitmap art, int size, float span, float cx, float cy, float radius, bool background) {
        var result = new Bitmap(size, size, PixelFormat.Format32bppArgb);
        using (var g = Canvas(result)) {
            g.Clear(background ? Ivory : Color.Transparent);
            float scale = size / span * 32f / radius;
            g.DrawImage(art, new RectangleF(size / 2f - cx * scale, size / 2f - cy * scale,
                art.Width * scale, art.Height * scale));
        }
        return result;
    }
    static Bitmap Mask(Bitmap square, bool round) {
        var result = new Bitmap(square.Width, square.Height, PixelFormat.Format32bppArgb);
        // Supersample mask coverage: no dark RGB fringe at transparent edges.
        double half = square.Width / 2.0;
        for (int y = 0; y < square.Height; y++) for (int x = 0; x < square.Width; x++) {
            int inside = 0;
            for (int sy = 0; sy < 4; sy++) for (int sx = 0; sx < 4; sx++) {
                double dx = Math.Abs((x + (sx + .5) / 4 - half) / half);
                double dy = Math.Abs((y + (sy + .5) / 4 - half) / half);
                double p = round ? 2 : 4;
                if (Math.Pow(dx, p) + Math.Pow(dy, p) <= 1) inside++;
            }
            Color c = square.GetPixel(x, y);
            result.SetPixel(x, y, Color.FromArgb((c.A * inside + 8) / 16, c.R, c.G, c.B));
        }
        return result;
    }
    static Bitmap AdaptivePreview(string foreground, int size) {
        var result = new Bitmap(size, size, PixelFormat.Format32bppArgb);
        using (var fg = new Bitmap(foreground))
        using (var g = Canvas(result)) {
            g.Clear(Ivory);
            // Actual 432px/108dp resource: default mask viewport is the central 72dp.
            g.DrawImage(fg, new Rectangle(0, 0, size, size), new Rectangle(72, 72, 288, 288), GraphicsUnit.Pixel);
        }
        return result;
    }
    public static void Generate(string source, string res, string preview) {
        Directory.CreateDirectory(preview);
        using (var original = new Bitmap(source))
        using (var art = new Bitmap(original.Width, original.Height, PixelFormat.Format32bppArgb)) {
            int w = original.Width, h = original.Height;
            bool[] background = new bool[w * h];
            var queue = new Queue<int>();
            Action<int> visit = i => {
                if (background[i]) return;
                Color c = original.GetPixel(i % w, i / w);
                int chroma = Math.Max(c.R, Math.Max(c.G, c.B)) - Math.Min(c.R, Math.Min(c.G, c.B));
                // Both the baked black corners and textured ivory are neutral.
                // Flooding only the exterior keeps the cream clapper intact.
                if (chroma > 40) return;
                background[i] = true;
                queue.Enqueue(i);
            };
            for (int x = 0; x < w; x++) { visit(x); visit((h - 1) * w + x); }
            for (int y = 0; y < h; y++) { visit(y * w); visit(y * w + w - 1); }
            while (queue.Count > 0) {
                int i = queue.Dequeue(), x = i % w, y = i / w;
                if (x > 0) visit(i - 1); if (x + 1 < w) visit(i + 1);
                if (y > 0) visit(i - w); if (y + 1 < h) visit(i + w);
            }
            int minX = w, minY = h, maxX = 0, maxY = 0;
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                if (background[y * w + x]) continue;
                Color c = original.GetPixel(x, y);
                art.SetPixel(x, y, c);
                minX = Math.Min(minX, x); minY = Math.Min(minY, y);
                maxX = Math.Max(maxX, x); maxY = Math.Max(maxY, y);
            }
            float cx = (minX + maxX) / 2f, cy = (minY + maxY) / 2f;
            double maxRadius = 0;
            for (int y = minY; y <= maxY; y++) for (int x = minX; x <= maxX; x++) {
                if (art.GetPixel(x, y).A == 0) continue;
                maxRadius = Math.Max(maxRadius, Math.Sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy)));
            }
            float radius = (float)maxRadius;
            Console.WriteLine("Source: {0}x{1}, {2}; art bounds: ({3},{4})-({5},{6})", w,h,original.PixelFormat,minX,minY,maxX,maxY);
            Console.WriteLine("All foreground fits centered 64dp circle inside official 66dp safe circle; adaptive canvas 108dp.");
            Console.WriteLine("Visible art: {0:F2} x {1:F2} dp; max radius 32dp.", (maxX-minX+1)*32/radius, (maxY-minY+1)*32/radius);
            string foregroundDir = Path.Combine(res, "drawable-xxxhdpi");
            Directory.CreateDirectory(foregroundDir);
            using (var fg = Render(art, 432, 108, cx, cy, radius, false))
                fg.Save(Path.Combine(foregroundDir, "ic_launcher_foreground.png"), ImageFormat.Png);
            string[] densities = { "mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi" };
            int[] sizes = { 48, 72, 96, 144, 192 };
            for (int i = 0; i < sizes.Length; i++) {
                string dir = Path.Combine(res, "mipmap-" + densities[i]);
                Directory.CreateDirectory(dir);
                // Legacy corresponds to the default adaptive viewport (central 72dp).
                using (var square = Render(art, sizes[i], 72, cx, cy, radius, true)) {
                    square.Save(Path.Combine(dir, "ic_launcher.png"), ImageFormat.Png);
                    using (var round = Mask(square, true)) round.Save(Path.Combine(dir, "ic_launcher_round.png"), ImageFormat.Png);
                }
            }
            // Local-only compositing proof, including actual 48px launcher scale.
            using (var sheet = new Bitmap(720, 420))
            using (var g = Canvas(sheet))
            using (var title = new Font("Segoe UI", 15))
            using (var text = new Font("Segoe UI", 11)) {
                g.Clear(Color.FromArgb(225, 230, 226));
                g.DrawString("Selected artwork / actual adaptive masks", title, Brushes.Black, 24, 16);
                using (var square = new Bitmap(Path.Combine(res, "mipmap-xxxhdpi", "ic_launcher.png")))
                using (var adaptive = AdaptivePreview(Path.Combine(foregroundDir, "ic_launcher_foreground.png"), 192))
                using (var round = Mask(adaptive, true))
                using (var squircle = Mask(adaptive, false)) {
                    g.DrawImage(square, 24, 65); g.DrawImage(round, 264, 65); g.DrawImage(squircle, 504, 65);
                }
                g.DrawString("Legacy square", text, Brushes.Black, 24, 272);
                g.DrawString("Round mask", text, Brushes.Black, 264, 272);
                g.DrawString("Squircle mask", text, Brushes.Black, 504, 272);
                using (var small = new Bitmap(Path.Combine(res, "mipmap-mdpi", "ic_launcher.png")))
                using (var adaptive = AdaptivePreview(Path.Combine(foregroundDir, "ic_launcher_foreground.png"), 48))
                using (var round = Mask(adaptive, true))
                using (var squircle = Mask(adaptive, false)) {
                    g.DrawImage(small, 24, 314); g.DrawImage(round, 264, 314); g.DrawImage(squircle, 504, 314);
                }
                g.DrawString("48px / no leaf or clapper clipping", text, Brushes.Black, 24, 382);
                sheet.Save(Path.Combine(preview, "launcher-mask-preview.png"), ImageFormat.Png);
            }
        }
    }
}
'@

$res = Join-Path $PSScriptRoot '../app/src/main/res'
[FarmLauncherIcon]::Generate((Resolve-Path $Source).Path, [IO.Path]::GetFullPath($res), $PreviewDirectory)
