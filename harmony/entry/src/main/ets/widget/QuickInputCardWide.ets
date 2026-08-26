// QuickInputCardWide — 快捷输入服务卡片 2x4 宽版布局
//
// 2x4 横版布局（2x2 竖版见 QuickInputCard.ets）：
//   ┌──────────────────────────────────────┐
//   │ ● Agent Name                   12ms   │  ← 连接状态点 + Agent 名 + 延迟
//   │ Tap to send a message     [ ✏ New Chat ] │  ← 提示 + 按钮同行右对齐
//   └──────────────────────────────────────┘
//
// 与 2x2 版共享 QuickInputFormAbility 的 FormBindingData 字段
// （agentName / isConnected / latencyLabel），点击同样 router 拉起主应用。

let storage = new LocalStorage();

@Entry(storage)
@Component
struct QuickInputCardWide {
  // FormBindingData 注入的数据字段（QuickInputFormAbility 填充）
  @LocalStorageProp('agentName') agentName: string = 'Agent Control Center';
  @LocalStorageProp('isConnected') isConnected: boolean = false;
  @LocalStorageProp('latencyLabel') latencyLabel: string = '';

  build() {
    Column() {
      // ── 顶部：连接状态 + Agent 名 + 延迟 ──
      Row() {
        Circle({ width: 8, height: 8 })
          .fill(this.isConnected ? '#10B981' : '#C4C6D0')
        Text(this.agentName)
          .fontSize(14)
          .fontWeight(500)
          .fontColor('#1B1B1F')
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .margin({ left: 6 })
          .layoutWeight(1)
        if (this.latencyLabel.length > 0) {
          Text(this.latencyLabel)
            .fontSize(10)
            .fontColor('#535F70')
        }
      }
      .width('100%')

      // ── 底部：快捷输入提示 + 新建对话按钮（同行，按钮右对齐） ──
      Row() {
        Text($r('app.string.card_send_hint'))
          .fontSize(12)
          .fontColor('#535F70')
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .layoutWeight(1)
        Button($r('app.string.card_new_chat'))
          .fontSize(14)
          .fontWeight(500)
          .fontColor('#FFFFFF')
          .backgroundColor('#185FA5')
          .borderRadius(12)
          .height(36)
          .padding({ left: 16, right: 16 })
          .margin({ left: 12 })
          .onClick(() => {
            postCardAction({
              'action': 'router',
              'abilityName': 'EntryAbility',
              'params': {
                'fromCard': true
              }
            });
          })
      }
      .width('100%')
      .margin({ top: 12 })
      .alignItems(VerticalAlign.Center)
    }
    .width('100%')
    .height('100%')
    .padding(12)
    .borderRadius(16)
    .backgroundColor('#FDFBFF')
    .justifyContent(FlexAlign.Center)
    .onClick(() => {
      // 整卡点击同样拉起主应用（扩大点击热区，与按钮等价）
      postCardAction({
        'action': 'router',
        'abilityName': 'EntryAbility',
        'params': {
          'fromCard': true
        }
      });
    })
  }
}
