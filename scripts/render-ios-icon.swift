// Renders the iOS app icon from the same drawing as Android's launcher glyph
// (androidApp/src/main/res/drawable/ic_launcher_foreground.xml on
// @color/ic_launcher_background): Orbit Fleet's mark, a hub, one orbit at half
// strength and three hosts (the amber one is waiting on you), on #1B2430, in
// the same 108-unit viewport, scaled to 1024 px. Opaque on purpose: App Store
// Connect refuses an icon with an alpha channel.
//
//   swift scripts/render-ios-icon.swift iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png
import CoreGraphics
import Foundation
import ImageIO

let size = 1024
let scale = CGFloat(size) / 108
let ctx = CGContext(
    data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0,
    space: CGColorSpace(name: CGColorSpace.sRGB)!,
    bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
)!
ctx.setFillColor(CGColor(srgbRed: 0x1B / 255.0, green: 0x24 / 255.0, blue: 0x30 / 255.0, alpha: 1))
ctx.fill(CGRect(x: 0, y: 0, width: size, height: size))
// Android's viewport is y-down; Core Graphics is y-up.
ctx.translateBy(x: 0, y: CGFloat(size))
ctx.scaleBy(x: scale, y: -scale)

let glyph = CGColor(srgbRed: 0xF2 / 255.0, green: 0xF4 / 255.0, blue: 0xF7 / 255.0, alpha: 1)
let amber = CGColor(srgbRed: 0xD2 / 255.0, green: 0x9B / 255.0, blue: 0x4A / 255.0, alpha: 1)
func disc(_ x: CGFloat, _ y: CGFloat, _ r: CGFloat, _ color: CGColor) {
    ctx.setFillColor(color)
    ctx.fillEllipse(in: CGRect(x: x - r, y: y - r, width: 2 * r, height: 2 * r))
}

// The orbit, at half strength.
ctx.setStrokeColor(glyph.copy(alpha: 0.5)!)
ctx.setLineWidth(5)
ctx.strokeEllipse(in: CGRect(x: 30, y: 30, width: 48, height: 48))
// The hub, then the hosts at -90, 30 and 150 degrees on the orbit.
disc(54, 54, 10, glyph)
disc(54, 30, 7.5, glyph)
disc(74.78, 66, 7.5, amber)
disc(33.22, 66, 7.5, glyph)

let out = URL(fileURLWithPath: CommandLine.arguments[1])
let dest = CGImageDestinationCreateWithURL(out as CFURL, "public.png" as CFString, 1, nil)!
CGImageDestinationAddImage(dest, ctx.makeImage()!, nil)
guard CGImageDestinationFinalize(dest) else { fatalError("could not write \(out.path)") }
