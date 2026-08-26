// 设计 token — 形状（对标 android Shape.kt，圆角 token）
// Material 3 Expressive 形状层级。
export class AppShape {
  static readonly xs: number = 4;   // 小标签 / chip
  static readonly s: number = 8;    // 按钮 / 输入框
  static readonly m: number = 12;   // 卡片
  static readonly l: number = 16;   // 大卡片 / 弹窗
  static readonly xl: number = 24;  // 全屏 sheet
  static readonly full: number = 9999; // 胶囊（FAB）
}
