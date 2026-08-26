// Marketplace feature 主屏（对标 android features/marketplace/MarketplaceScreen.kt + ios MarketplaceScreen.swift）
// 占位阶段：显示标题栏 + Coming Soon 占位
import { AppColors } from 'common';

@Component
export struct MarketplaceScreen {
  build(): void {
    Column(): void {
      Row(): void {
        Text('Marketplace')
          .fontSize(20)
          .fontWeight(FontWeight.Bold)
          .fontColor(AppColors.onSurface)
      }
      .width('100%')
      .height(56)
      .padding({ left: 16, right: 16 })
      .justifyContent(FlexAlign.Start)
      .alignItems(VerticalAlign.Center)

      Column(): void {
        Text('Marketplace — Coming Soon')
          .fontSize(16)
          .fontColor(AppColors.outline)
          .margin({ top: 120 })
      }
      .width('100%')
      .layoutWeight(1)
      .justifyContent(FlexAlign.Start)
      .alignItems(HorizontalAlign.Center)
    }
    .width('100%')
    .height('100%')
    .backgroundColor(AppColors.background)
  }
}
