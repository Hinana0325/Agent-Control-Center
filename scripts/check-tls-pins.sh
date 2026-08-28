#!/usr/bin/env bash
#
# check-tls-pins.sh
#
# 校验 Android 与 iOS 两端的 TLS 证书锁定（pin）列表是否一致且格式合法。
#
#   Android: android/app/src/main/java/com/agentcontrolcenter/app/transport/http/CertificatePinnerFactory.kt
#   iOS:     ios/AgentControlCenter/Transport/TLSPinningDelegate.swift
#   契约:    protocol/transport/tls-pinning.md（pin 列表的单一事实来源）
#
# 校验项：
#   1. 双端登记的 host 集合完全一致
#   2. 同一 host 双端 pin 集合完全一致（含顺序无关比较）
#   3. host key 全小写（大写 key 会导致锁定静默失效，见下方说明）
#   4. 真实（非占位）pin 格式合法：`sha256/` + 44 字符 Base64
#   5. 每个 host 至少 2 个 pin（primary + backup，契约 §3.2 要求，缺 backup
#      时服务端一换密钥对就会全网断连）
#
# 关于占位 pin：当前仓库的 pin 为 PLACEHOLDER 占位值（真实 pin 必须在可信网络
# 下用 `scripts/fetch-tls-pins.sh` 获取）。占位本身**不算失败**——否则 CI 会长期
# 红灯，反而掩盖真正的漂移。默认仅告警；用 --strict 可让占位也判失败，用于发布门禁。
#
# 用法（从仓库根目录）：
#   bash scripts/check-tls-pins.sh            # 占位仅告警，退出 0
#   bash scripts/check-tls-pins.sh --strict   # 占位也判失败（发布前门禁）
#
# 退出码：0 通过 / 1 存在错误

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

ANDROID_FILE="$REPO_ROOT/android/app/src/main/java/com/agentcontrolcenter/app/transport/http/CertificatePinnerFactory.kt"
IOS_FILE="$REPO_ROOT/ios/AgentControlCenter/Transport/TLSPinningDelegate.swift"

STRICT=0
[[ "${1:-}" == "--strict" ]] && STRICT=1

errors=0
warnings=0

report_error() {
    echo "✗ $*"
    errors=$((errors + 1))
}

report_warning() {
    echo "⚠ $*"
    warnings=$((warnings + 1))
}

# ---------------------------------------------------------------------------
# parse_pins MODE FILE
#   解析源码中的 pin 列表，输出 "HOST|PIN" 行（每行一条）。
#
#   解析说明：
#   - 先剥离 `//` 行注释，避免把被注释掉的候选条目（如未启用的
#     generativelanguage.googleapis.com）算入当前生效列表。
#   - Android 条目以 put("HOST", listOf( 起始，到 )) 结束。
#   - iOS 条目以 "HOST": [ 起始，到 ] 结束。
# ---------------------------------------------------------------------------
parse_pins() {
    local mode="$1" file="$2"
    awk -v mode="$mode" '
        function trim(s) { gsub(/^[ \t]+/, "", s); gsub(/[ \t]+$/, "", s); return s }
        {
            line = $0
            sub(/\/\/.*$/, "", line)          # 剥离行注释
            line = trim(line)
            if (line == "") next

            if (mode == "android") {
                if (line ~ /^put\("[^"]+",[ \t]*listOf\(/) {
                    h = line
                    sub(/^put\("/, "", h)
                    sub(/".*$/, "", h)
                    host = h; incollect = 1; next
                }
                if (incollect) {
                    if (line ~ /^\)\)/) { incollect = 0; next }
                    # 注意：这里刻意匹配**任意**引号内容，而非只匹配 "sha256/..."
                    # 前缀。只匹配 sha256 会让写错的 pin（如 md5/xxx）被静默丢弃，
                    # 校验层只能看到「条目数量不足」这种误导性错误，而无法指出
                    # 真正的格式问题。把格式判定留给校验层，才能给出准确信息。
                    if (match(line, /"[^"]*"/)) {
                        p = substr(line, RSTART + 1, RLENGTH - 2)
                        print host "|" p
                    }
                    next
                }
            } else {
                if (line ~ /^"[^"]+":[ \t]*\[/) {
                    h = line
                    sub(/^"/, "", h)
                    sub(/".*$/, "", h)
                    host = h; incollect = 1; next
                }
                if (incollect) {
                    if (line ~ /^\]/) { incollect = 0; next }
                    # 同 Android 分支：匹配任意引号内容，格式判定交由校验层
                    if (match(line, /"[^"]*"/)) {
                        p = substr(line, RSTART + 1, RLENGTH - 2)
                        print host "|" p
                    }
                    next
                }
            }
        }
    ' "$file"
}

# ---------------------------------------------------------------------------
# main
#
# 注意：主体必须包在函数内。此前曾把 `local` 直接写在顶层作用域——bash 的
# `local` 只在函数中合法，在 `set -e` 下该行会**静默终止脚本**（stderr 一行
# "local: can only be used in a function"，退出码 1 却没有任何错误报告）。
# 结果是 pin 格式非法时脚本失败但不说明原因，比不校验更糟。
# ---------------------------------------------------------------------------
main() {
# ---------------------------------------------------------------------------
# 前置检查
# ---------------------------------------------------------------------------
for f in "$ANDROID_FILE" "$IOS_FILE"; do
    if [[ ! -f "$f" ]]; then
        echo "✗ 文件不存在：$f"
        return 1
    fi
done

echo "── TLS 证书锁定 pin 一致性校验 ───────────────────────────"
echo ""

android_raw="$(parse_pins android "$ANDROID_FILE")"
ios_raw="$(parse_pins ios "$IOS_FILE")"

if [[ -z "$android_raw" && -z "$ios_raw" ]]; then
    report_error "双端均未解析到任何 pin 条目——解析器可能已失效（源码格式变更？）"
    echo ""
    echo "✗ 校验失败：$errors 个错误"
    return 1
fi

android_hosts="$(echo "$android_raw" | cut -d'|' -f1 | sort -u)"
ios_hosts="$(echo "$ios_raw" | cut -d'|' -f1 | sort -u)"

# ---------------------------------------------------------------------------
# 1. host 集合一致性
# ---------------------------------------------------------------------------
echo "── 1. 双端 host 集合 ─────────────────────────────────────"
if [[ "$android_hosts" != "$ios_hosts" ]]; then
    only_android="$(comm -23 <(echo "$android_hosts") <(echo "$ios_hosts"))"
    only_ios="$(comm -13 <(echo "$android_hosts") <(echo "$ios_hosts"))"
    [[ -n "$only_android" ]] && report_error "仅 Android 登记：$(echo "$only_android" | tr '\n' ' ')"
    [[ -n "$only_ios" ]] && report_error "仅 iOS 登记：$(echo "$only_ios" | tr '\n' ' ')"
else
    echo "✓ 双端登记 $(echo "$android_hosts" | grep -c . ) 个 host：$(echo "$android_hosts" | tr '\n' ' ')"
fi
echo ""

# ---------------------------------------------------------------------------
# 2-5. 逐 host 校验
# ---------------------------------------------------------------------------
echo "── 2. 逐 host 校验（一致性 / 小写 / 格式 / 数量）─────────"

placeholder_total=0

while IFS= read -r host; do
    [[ -z "$host" ]] && continue

    android_pins="$(echo "$android_raw" | awk -F'|' -v h="$host" '$1==h {print $2}' | sort)"
    ios_pins="$(echo "$ios_raw" | awk -F'|' -v h="$host" '$1==h {print $2}' | sort)"

    echo ""
    echo "  [$host]"
    echo "      Android: $(echo "$android_pins" | wc -l | tr -d ' ') 个 pin"
    echo "      iOS    : $(echo "$ios_pins" | wc -l | tr -d ' ') 个 pin"

    # 2. 同 host 双端 pin 集合一致
    if [[ "$android_pins" != "$ios_pins" ]]; then
        report_error "[$host] 双端 pin 不一致"
        comm -3 <(echo "$android_pins") <(echo "$ios_pins") | sed 's/^/          /'
    else
        echo "      ✓ 双端一致"
    fi

    # 3. host key 必须全小写
    #    OkHttp（Android）会把 host 规范化为小写后再匹配 pinner；iOS 侧
    #    TLSPinningDelegate 也已 lowercased() 后查表。大写 key 将永远匹配不到，
    #    导致该主机的锁定**静默失效**——比显式不锁定更危险。
    if [[ "$host" != "${host,,}" ]]; then
        report_error "[$host] host key 含大写字母，锁定将静默失效（必须全小写）"
    fi

    # 4-5. 逐个 pin 校验格式与占位状态
    local_placeholders=0
    while IFS= read -r pin; do
        [[ -z "$pin" ]] && continue
        if [[ "$pin" == *"PLACEHOLDER"* || "$pin" == *"REPLACE_WITH"* ]]; then
            local_placeholders=$((local_placeholders + 1))
            continue
        fi
        # 格式：sha256/ + 44 字符 Base64（SHA-256 = 32 字节）
        if [[ ! "$pin" =~ ^sha256/[A-Za-z0-9+/]+=$ ]]; then
            report_error "[$host] pin 格式非法：$pin"
            continue
        fi
        local b64="${pin#sha256/}"
        if [[ ${#b64} -ne 44 ]]; then
            report_error "[$host] pin 的 Base64 长度应为 44，实际 ${#b64}：$pin"
        fi
    done <<< "$android_pins"

    # 5. pin 数量与去重
    #    backup pin 的全部意义在于「primary 失效时仍有备选」，与 primary 填成
    #    同一个值等于没有 backup——服务端一换密钥对照样全网断连。契约 §3.2
    #    要求 primary 与 backup 为**两个不同**的公钥。
    total_count="$(echo "$android_pins" | grep -c . || true)"
    unique_count="$(echo "$android_pins" | sort -u | grep -c . || true)"
    if [[ "$total_count" -lt 2 ]]; then
        report_error "[$host] 仅 $total_count 个 pin，契约 §3.2 要求 primary + backup 至少 2 个"
    elif [[ "$unique_count" -lt 2 ]]; then
        report_error "[$host] primary 与 backup 相同，backup 形同虚设（契约 §3.2 要求两个不同公钥）"
    fi

    if [[ $local_placeholders -gt 0 ]]; then
        placeholder_total=$((placeholder_total + local_placeholders))
        echo "      ⚠ $local_placeholders 个占位 pin —— 证书锁定当前**未生效**"
        info_hint=1
    fi
done <<< "$android_hosts"

echo ""
echo "──────────────────────────────────────────────────────────"

# ---------------------------------------------------------------------------
# 汇总
# ---------------------------------------------------------------------------
if [[ $placeholder_total -gt 0 ]]; then
    echo ""
    echo "⚠ 检测到 $placeholder_total 个占位 pin —— 证书锁定当前未生效。"
    echo ""
    echo "  两端代码已有完整的 pinning 接线（Android TransportFactory 动态启用、"
    echo "  iOS 三处 transport 注入 delegate），但 pin 为空使保护为零。"
    echo "  这一状态本身是安全的（占位 pin 不会导致连接失败），只是未提供保护。"
    echo ""
    echo "  填入真实 pin（⚠️ 必须在无代理的可信网络下执行）："
    echo "      bash scripts/fetch-tls-pins.sh api.openai.com"
    echo ""
    echo "  然后同步更新 protocol/transport/tls-pinning.md 第 4 节的 pin 列表。"
    echo ""
    if [[ $STRICT -eq 1 ]]; then
        report_error "存在占位 pin（--strict 模式下判为失败）"
    else
        report_warning "存在占位 pin（用 --strict 可将其判为失败，用于发布门禁）"
    fi
fi

echo ""
if [[ $errors -gt 0 ]]; then
    echo "✗ 校验失败：$errors 个错误、$warnings 个警告"
    return 1
fi

echo "✓ 校验通过（$warnings 个警告）"
return 0
}

main "$@"
