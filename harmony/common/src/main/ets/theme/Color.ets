// 设计 token — 颜色（对标 android Color.kt + iOS AppTheme.swift）
// v5.0 语义色 token，随 Light/Dark 切换。hex 与双端完全对齐。
export class AppColors {
  // ── 强调色（AccentBlue 调色板，对标 Android AccentBlue） ──
  static readonly primary: string = '#185FA5';
  static readonly primaryContainer: string = '#D1E4FF';
  static readonly onPrimary: string = '#FFFFFF';
  static readonly onPrimaryContainer: string = '#001B3D';
  static readonly secondary: string = '#535F70';
  static readonly secondaryContainer: string = '#D7E3F7';
  static readonly tertiary: string = '#6B5778';
  static readonly tertiaryContainer: string = '#F2DAFF';

  // ── Light 表面 ──
  static readonly lightBackground: string = '#FDFBFF';
  static readonly lightSurface: string = '#FDFBFF';
  static readonly lightSurfaceVariant: string = '#F1F0F4';
  static readonly lightOnBackground: string = '#1B1B1F';
  static readonly lightOutline: string = '#C4C6D0';
  static readonly lightOutlineVariant: string = '#E0E0E6';

  // ── Dark 表面 ──
  static readonly darkBackground: string = '#1B1B1F';
  static readonly darkSurface: string = '#1B1B1F';
  static readonly darkSurfaceVariant: string = '#2B2930';
  static readonly darkOnBackground: string = '#E4E1E6';
  static readonly darkOutline: string = '#8E9099';
  static readonly darkOutlineVariant: string = '#44474E';

  // ── v5.0 语义色 ──
  static readonly successLight: string = '#10B981';
  static readonly successDark: string = '#34D399';
  static readonly warningLight: string = '#F59E0B';
  static readonly warningDark: string = '#FBBF24';
  static readonly infoLight: string = '#3B82F6';
  static readonly infoDark: string = '#60A5FA';
  static readonly dangerLight: string = '#FF6B35';
  static readonly dangerDark: string = '#FF8C5A';

  // ── 默认引用别名（供未做主题切换的占位页面使用） ──
  // H3 阶段引入 ThemeManager 后会按系统深浅色切换。
  static readonly background: string = AppColors.lightBackground;
  static readonly surface: string = AppColors.lightSurface;
  static readonly onBackground: string = AppColors.lightOnBackground;
  static readonly onSurface: string = AppColors.lightOnBackground;
  static readonly outline: string = AppColors.lightOutline;
  static readonly success: string = AppColors.successLight;
  static readonly warning: string = AppColors.warningLight;
  static readonly info: string = AppColors.infoLight;
  static readonly danger: string = AppColors.dangerLight;
}
