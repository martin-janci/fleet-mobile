// Renders the iOS app icon from the same drawing as Android's launcher glyph
// (androidApp/src/main/res/drawable/ic_launcher_foreground.xml on
// @color/ic_launcher_background): a white shell prompt on #1B2430, in the
// same 108-unit viewport, scaled to 1024 px. Opaque on purpose: App Store
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
ctx.setStrokeColor(CGColor(srgbRed: 1, green: 1, blue: 1, alpha: 1))
ctx.setLineWidth(7)
ctx.setLineCap(.round)
ctx.setLineJoin(.round)
ctx.move(to: CGPoint(x: 34, y: 40)); ctx.addLine(to: CGPoint(x: 50, y: 54)); ctx.addLine(to: CGPoint(x: 34, y: 68))
ctx.strokePath()
ctx.move(to: CGPoint(x: 56, y: 68)); ctx.addLine(to: CGPoint(x: 74, y: 68))
ctx.strokePath()

let out = URL(fileURLWithPath: CommandLine.arguments[1])
let dest = CGImageDestinationCreateWithURL(out as CFURL, "public.png" as CFString, 1, nil)!
CGImageDestinationAddImage(dest, ctx.makeImage()!, nil)
guard CGImageDestinationFinalize(dest) else { fatalError("could not write \(out.path)") }
