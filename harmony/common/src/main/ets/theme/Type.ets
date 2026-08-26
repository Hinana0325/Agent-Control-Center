// 设计 token — 排版（对标 android Type.kt + iOS AppTheme.swift 字体）
// 字号以 fp 为单位，鸿蒙 ArkUI 默认单位即 fp。
export class AppType {
  // 字号
  static readonly displayLarge: number = 32;
  static readonly displayMedium: number = 28;
  static readonly displaySmall: number = 24;
  static readonly headlineLarge: number = 22;
  static readonly headlineMedium: number = 20;
  static readonly headlineSmall: number = 18;
  static readonly bodyLarge: number = 16;
  static readonly bodyMedium: number = 14;
  static readonly bodySmall: number = 12;
  static readonly labelLarge: number = 14;
  static readonly labelMedium: number = 12;
  static readonly labelSmall: number = 10;

  // 字重（鸿蒙用 0-900 数值，对标 Compose FontWeight）
  static readonly regular: number = 400;
  static readonly medium: number = 500;
  static readonly semibold: number = 600;
  static readonly bold: number = 700;
}
