// QuickInputCard — FormKit 快捷输入服务卡片 UI（对标 android Widget 快捷输入卡片）
//
// 2x2 竖版布局（2x4 宽版见 QuickInputCardWide.ets）：
//   ┌──────────────────────┐
//   │ ● Agent Name     12ms │  ← 连接状态点 + Agent 名 + 延迟
//   │ Tap to send a message │  ← 快捷输入提示
//   │        [ ✏ New Chat ] │  ← 点击 router 拉起主应用 Chat 页
//   └──────────────────────┘
//
// 鸿蒙卡片为静态 UI + 事件（不支持 TextInput），点击通过 postCardAction
// router 拉起主应用 EntryAbility，对齐 Android WidgetInputActivity 入口语义。

let storage = new LocalStorage();

@Entry(storage)
@Component
struct QuickInputCard {
  // FormBindingData 注入的数据字段（QuickInputFormAbility 填充）
  @LocalStorageProp('agentName') agentName: string = 'Agent Control Center';
  @LocalStorageProp('isConnected') isConnected: boolean = false;
  @LocalStorageProp('latencyLabel') latencyLabel: string = '';

  build() {
    Column() {
      // ── 顶部：连接状态 + Agent 名 ──
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

      // ── 中部：快捷输入提示 ──
      Text($r('app.string.card_send_hint'))
        .fontSize(12)
        .fontColor('#535F70')
        .maxLines(1)
        .margin({ top: 8 })

      // ── 底部：新建对话按钮（router 拉起主应用） ──
      Button($r('app.string.card_new_chat'))
        .fontSize(14)
        .fontWeight(500)
        .fontColor('#FFFFFF')
        .backgroundColor('#185FA5')
        .borderRadius(12)
        .height(36)
        .width('100%')
        .margin({ top: 12 })
        .onClick((): void => {
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
    .height('100%')
    .padding(12)
    .borderRadius(16)
    .backgroundColor('#FDFBFF')
    .alignItems(HorizontalAlign.Start)
    .justifyContent(FlexAlign.Center)
    .onClick((): void => {
      // 整卡点击同样拉起主应用（与按钮等价，扩大点击热区）
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
