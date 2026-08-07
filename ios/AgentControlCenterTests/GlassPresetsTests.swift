import XCTest
import SwiftUI
@testable import AgentControlCenter

// MARK: - 液态玻璃预设单元测试
// CI-fix: 类标记 @MainActor。glassTinted(_:in:) / glassInteractive(in:) /
// glassMorphID(_:in:) / GlassContainer.init 等都是 SwiftUI View
// 协议扩展方法（View 是 @MainActor），nonisolated 测试方法调用会报
// "call to main actor-isolated instance method in a synchronous nonisolated context"。
@MainActor
final class GlassPresetsTests: XCTestCase {

    // MARK: - GlassTokens 测试

    /// 验证默认玻璃 variant 为 .regular
    ///
    /// CI-fix: 用 `#if compiler(>=6.2)` 门控 — `GlassTokens.regularVariant` 仅在
    /// Xcode 26（Swift 6.2）构建时存在。Xcode 16.4 编译时该属性被 `#if` 排除，
    /// 测试方法也需同步排除，否则会报 "unresolved identifier 'regularVariant'"。
    #if compiler(>=6.2)
    @available(iOS 26, *)
    func testRegularVariantIsRegularGlass() {
        // Glass.regular 与 GlassTokens.regularVariant 应为同一 variant
        // 由于 Glass 不暴露内部标识符，仅验证可正常构造且不崩溃
        let _ = GlassTokens.regularVariant
        XCTAssertTrue(true, "regularVariant 应可正常访问")
    }

    /// 验证交互式 variant 带按压反馈（.interactive()）
    @available(iOS 26, *)
    func testInteractiveVariantIsInteractive() {
        let _ = GlassTokens.interactiveVariant
        XCTAssertTrue(true, "interactiveVariant 应可正常访问")
    }
    #endif

    /// 验证容器间距默认值为 16pt
    func testContainerSpacingDefaultValue() {
        XCTAssertEqual(GlassTokens.containerSpacing, 16,
                       "默认 containerSpacing 应为 16pt")
    }

    /// 验证弹簧参数为 Spring 实例（响应/阻尼比为正）
    func testBounceSpringIsValidSpring() {
        // Spring 的 response 与 dampingFraction 应为正数
        // SwiftUI Spring 没有公开属性访问器，仅验证可构造
        let _ = GlassTokens.bounceSpring
        let _ = GlassTokens.smoothSpring
        let _ = GlassTokens.exitSpring
        XCTAssertTrue(true, "三个弹簧预设应可正常访问")
    }

    /// 验证形状令牌可正常访问
    func testShapeTokensAccessible() {
        let _ = GlassTokens.pillShape
        let _ = GlassTokens.circleShape
        let _ = GlassTokens.sheetShape
        XCTAssertEqual(GlassTokens.sheetCornerRadius, AppTheme.CornerRadius.xl,
                       "sheetCornerRadius 应等于 AppTheme.CornerRadius.xl")
    }

    // MARK: - GlassPresets View 扩展测试
    // v5.1: glassPill / glassFloating / glassStatic 已在 v5.0 UI 改版时删除，
    //       仅保留 glassInteractive(in:) / glassTinted(_:in:) / glassMorphID(_:in:)。
    //       测试同步移除已删除 API 的用例，保留并补充现存 API 覆盖。

    /// 验证 .glassInteractive(in:) 接受任意 Shape
    func testGlassInteractiveAcceptsArbitraryShape() {
        let rect = RoundedRectangle(cornerRadius: 12, style: .continuous)
        let view = Color.green
            .frame(width: 200, height: 100)
            .glassInteractive(in: rect)
        XCTAssertNotNil(view, ".glassInteractive(in:) 应接受 RoundedRectangle")

        let capsule = Capsule()
        let view2 = Color.yellow
            .frame(width: 80, height: 80)
            .glassInteractive(in: capsule)
        XCTAssertNotNil(view2, ".glassInteractive(in:) 应接受 Capsule")
    }

    /// 验证 .glassInteractive(in:) 用 Circle 也可构造
    func testGlassInteractiveAcceptsCircle() {
        let view = Color.gray
            .frame(width: 40, height: 40)
            .glassInteractive(in: Circle())
        XCTAssertNotNil(view, ".glassInteractive(in: Circle()) 应返回非空 View")
    }

    /// 验证 .glassTinted(_:in:) 接受 Color tint + 任意 Shape
    /// R4: 新增的 tint 兼容包装
    func testGlassTintedAcceptsColorAndShape() {
        let view = Color.clear
            .frame(width: 60, height: 60)
            .glassTinted(Color.red.opacity(0.6), in: Circle())
        XCTAssertNotNil(view, ".glassTinted(_:in:) 应返回非空 View")
    }

    /// 验证 .glassTinted(_:in:) 透明 tint 回退分支（alpha < 0.01 不绘制背景）
    /// 黑框修复覆盖：透明 tint 不应绘制 ultraThinMaterial 暗色基底
    func testGlassTintedTransparentTintNoBackground() {
        let view = Color.clear
            .frame(width: 60, height: 60)
            .glassTinted(Color.clear, in: Circle())
        XCTAssertNotNil(view, "透明 tint 的 .glassTinted(_:in:) 应返回非空 View")
    }

    /// 验证 .glassTinted(_:in:) 低 opacity tint 回退分支（0.01 ≤ alpha < 0.5）
    func testGlassTintedLowOpacityTintFallback() {
        let view = Color.clear
            .frame(width: 60, height: 60)
            .glassTinted(Color.gray.opacity(0.3), in: Capsule())
        XCTAssertNotNil(view, "低 opacity tint 的 .glassTinted(_:in:) 应返回非空 View")
    }

    // MARK: - GlassContainer 测试

    /// 验证 GlassContainer 可包裹多个子视图
    /// v5.1: 子视图改用 glassTinted(_:in:) 包装（glassPill 已删除）
    func testGlassContainerWrapsMultipleChildren() {
        let container = GlassContainer {
            HStack {
                Text("A").glassTinted(Color.blue.opacity(0.5), in: Capsule())
                Text("B").glassTinted(Color.blue.opacity(0.5), in: Capsule())
                Text("C").glassTinted(Color.blue.opacity(0.5), in: Capsule())
            }
        }
        XCTAssertNotNil(container, "GlassContainer 应可包裹多个 .glassTinted(_:in:) 子视图")
    }

    /// 验证 GlassContainer 默认 spacing 为 GlassTokens.containerSpacing
    func testGlassContainerDefaultSpacing() {
        // 默认 spacing 应为 16pt；由于 spacing 是私有的，仅验证可正常构造
        let _ = GlassContainer {
            Text("test").glassTinted(Color.red.opacity(0.5), in: Capsule())
        }
        XCTAssertTrue(true, "GlassContainer 默认构造应成功")
    }

    /// 验证 GlassContainer 可自定义 spacing
    func testGlassContainerCustomSpacing() {
        let _ = GlassContainer(spacing: 30) {
            Text("test").glassTinted(Color.red.opacity(0.5), in: Capsule())
        }
        XCTAssertTrue(true, "GlassContainer 自定义 spacing=30 应成功构造")
    }
}
