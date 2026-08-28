#!/usr/bin/env bash
#
# fetch-tls-pins.sh
#
# 获取指定域名的 SPKI 证书锁定（Certificate Pinning）pin 值，输出可直接粘贴到
# Android `CertificatePinnerFactory.PUBLIC_API_PINS` 与 iOS
# `TLSPinningDelegate.PUBLIC_API_PINS` 的代码片段。
#
#   Android: android/app/src/main/java/com/agentcontrolcenter/app/transport/http/CertificatePinnerFactory.kt
#   iOS:     ios/AgentControlCenter/Transport/TLSPinningDelegate.swift
#   契约:    protocol/transport/tls-pinning.md
#
# ===========================================================================
#  ⚠️  必须在可信网络环境下运行
# ===========================================================================
#
# 本脚本获取到的 pin 会被写入源码，一旦错误，所有用户的对应域名连接将**全部被拒**
# 且只能通过发新版修复。以下环境会导致获取到**错误的** pin：
#
#   - MITM 代理 / 企业 DLP 网关（拿到代理自己的证书）
#   - 抓包工具（Charles / mitmproxy / Fiddler）
#   - DNS 劫持或 fake-ip 模式的分流工具（连接根本没到达真实服务器）
#   - CI 沙箱 / 容器（出网被透明代理，DNS 返回保留地址）
#
# 因此脚本内置了三道拦截（见 check_environment），任一触发即**中止且不输出 pin**。
# 宁可拒绝产出，不可产出错误的安全常量。
#
# ===========================================================================
#
# 用法（从仓库根目录）：
#
#   bash scripts/fetch-tls-pins.sh [HOST] [PORT]
#
#   HOST  默认 api.openai.com
#   PORT  默认 443
#
# 示例：
#   bash scripts/fetch-tls-pins.sh
#   bash scripts/fetch-tls-pins.sh generativelanguage.googleapis.com
#
# 自检（不联网，用本地生成的证书验证 pin 算法）：
#   bash scripts/fetch-tls-pins.sh --self-test
#
# 退出码：0 成功 / 1 环境不可信或获取失败 / 2 用法错误

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

ANDROID_FILE="$REPO_ROOT/android/app/src/main/java/com/agentcontrolcenter/app/transport/http/CertificatePinnerFactory.kt"
IOS_FILE="$REPO_ROOT/ios/AgentControlCenter/Transport/TLSPinningDelegate.swift"

DEFAULT_HOST="api.openai.com"
DEFAULT_PORT="443"

# ---------------------------------------------------------------------------
# 工具函数
# ---------------------------------------------------------------------------

die() {
    echo ""
    echo "✗ $*" >&2
    exit 1
}

warn() {
    echo "⚠ $*"
}

info() {
    echo "  $*"
}

# pin_of CERT_PEM_FILE
#   计算证书公钥（SPKI）的 SHA-256 Base64，即 OkHttp / SecTrust pin 语义。
#   等价链：x509 -pubkey -> pkey -pubin -outform der -> dgst -sha256 -> base64
#   与 Android `X509Certificate.getPublicKey().getEncoded()` 及 iOS
#   `TLSPinningDelegate.spkiSha256Base64(of:)` 逐字节一致。
pin_of() {
    local cert_file="$1"
    openssl x509 -in "$cert_file" -pubkey -noout 2>/dev/null \
        | openssl pkey -pubin -outform der 2>/dev/null \
        | openssl dgst -sha256 -binary \
        | openssl enc -base64
}

# subject_of / issuer_of / dates_of：供人工核对证书身份，防止拿到代理证书
subject_of() {
    openssl x509 -in "$1" -noout -subject 2>/dev/null | sed 's/^subject=//'
}

issuer_of() {
    openssl x509 -in "$1" -noout -issuer 2>/dev/null | sed 's/^issuer=//'
}

dates_of() {
    local nb na
    nb=$(openssl x509 -in "$1" -noout -startdate 2>/dev/null | sed 's/^notBefore=//')
    na=$(openssl x509 -in "$1" -noout -enddate 2>/dev/null | sed 's/^notAfter=//')
    echo "$nb → $na"
}

# is_private_or_reserved IP
#   判断 IPv4 是否位于私有段或保留段。命中意味着 DNS 被劫持 / 处于 fake-ip
#   分流环境，连接不会到达真实服务器。
is_private_or_reserved() {
    local ip="$1"
    local o1 o2
    [[ "$ip" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] || return 1
    o1="${BASH_REMATCH[1]}"
    o2="${BASH_REMATCH[2]}"

    # 私有地址段 RFC 1918
    [[ "$o1" == 10 ]] && return 0
    [[ "$o1" == 192 && "$o2" == 168 ]] && return 0
    [[ "$o1" == 172 && "$o2" -ge 16 && "$o2" -le 31 ]] && return 0
    # 回环 / 链路本地 / CGNAT（RFC 6598）
    [[ "$o1" == 127 ]] && return 0
    [[ "$o1" == 169 && "$o2" == 254 ]] && return 0
    [[ "$o1" == 100 && "$o2" -ge 64 && "$o2" -le 127 ]] && return 0
    # 网络设备基准测试段 RFC 2544 —— clash 等工具的 fake-ip 默认段
    [[ "$o1" == 198 && "$o2" -ge 18 && "$o2" -le 19 ]] && return 0
    # 保留 / 文档专用段
    [[ "$o1" == 0 ]] && return 0
    [[ "$o1" -ge 224 ]] && return 0
    return 1
}

# ---------------------------------------------------------------------------
# check_environment HOST
#   三道拦截。任一触发即中止：宁可拒绝产出，不可产出错误的安全常量。
# ---------------------------------------------------------------------------
check_environment() {
    local host="$1"
    local rc=0

    echo "── 环境可信性检查 ──────────────────────────────────────────"

    # 拦截 1：显式代理环境变量
    local proxy_vars=()
    local v
    for v in http_proxy https_proxy HTTP_PROXY HTTPS_PROXY ALL_PROXY all_proxy; do
        if [[ -n "${!v:-}" ]]; then
            proxy_vars+=("$v=${!v}")
        fi
    done
    if [[ ${#proxy_vars[@]} -gt 0 ]]; then
        echo "✗ 检测到代理环境变量，连接可能被解密重签："
        for v in "${proxy_vars[@]}"; do info "$v"; done
        echo "  请在无代理环境下重跑（unset 上述变量），脚本中止。"
        rc=1
    else
        echo "✓ 未检测到代理环境变量"
    fi

    # 拦截 2：DNS 解析到私有 / 保留地址（fake-ip 或 DNS 劫持）
    local resolved
    resolved="$(getent ahostsv4 "$host" 2>/dev/null | awk '{print $1}' | sort -u | head -3 || true)"
    if [[ -z "$resolved" ]]; then
        echo "✗ 无法解析 $host（DNS 失败）。脚本中止。"
        return 1
    fi
    local bad_ip=""
    local ip
    for ip in $resolved; do
        if is_private_or_reserved "$ip"; then
            bad_ip="$ip"
            break
        fi
    done
    if [[ -n "$bad_ip" ]]; then
        echo "✗ $host 解析到私有/保留地址 $bad_ip"
        echo "  这通常是 fake-ip 分流工具、DNS 劫持或沙箱网络策略所致——连接不会"
        echo "  到达真实服务器，此时抓到的证书不是目标服务器的证书。脚本中止。"
        return 1
    fi
    echo "✓ DNS 解析为公网地址：$(echo "$resolved" | tr '\n' ' ')"

    # 拦截 3：本机 CA 库中存在可疑的额外根证书（企业 DLP 常见特征）
    # 仅作软提示，不作为中止条件——合法企业环境也有此情况，交由人工判断。
    local ca_count
    ca_count="$(ls /usr/local/share/ca-certificates/*.crt 2>/dev/null | wc -l || echo 0)"
    if [[ "$ca_count" -gt 0 ]]; then
        warn "本机存在 $ca_count 个额外根证书（/usr/local/share/ca-certificates/）。"
        info "若其中包含企业代理 CA，本脚本将拿到重签后的证书。请自行确认。"
    fi

    # 注意：不要写成 `[[ $rc -eq 0 ]] && echo ...` ——在 `set -e` 下该 AND list
    # 短路时返回非 0，会依赖 set -e 的边界行为提前终止，可读性差且易误判。
    if [[ $rc -eq 0 ]]; then
        echo "──────────────────────────────────────────────────────────"
    fi
    return $rc
}

# ---------------------------------------------------------------------------
# fetch_chain HOST PORT
#   抓取证书链；强制做主机名与 CA 链校验（-verify_return_error -verify_hostname）。
#   校验不通过即视为 MITM，中止。
# ---------------------------------------------------------------------------
fetch_chain() {
    local host="$1" port="$2"
    local out verify_line return_code

    out="$(echo | timeout 25 openssl s_client \
        -connect "$host:$port" \
        -servername "$host" \
        -showcerts \
        -verify_return_error \
        -verify_hostname "$host" 2>&1)" || {
        echo "$out" | grep -E "verify error|Verify return code|SSL alert|unexpected eof" | head -5 >&2 || true
        die "TLS 握手或证书校验失败——$host:$port 不可达，或你正处于被解密的网络中。
  未产出任何 pin（错误的 pin 比没有 pin 危险得多）。"
    }

    verify_line="$(echo "$out" | grep -E "Verify return code" | head -1 || true)"
    return_code="$(echo "$verify_line" | grep -oE '\(([0-9]+|ok)\)' | tr -d '()' || true)"

    if [[ "$return_code" != "ok" && "$return_code" != "0" ]]; then
        echo "$out" | grep -E "verify error|Verify return code" | head -5 >&2
        die "证书链校验未通过（$verify_line）。
  可能原因：网络被 MITM 解密、系统 CA 库过旧、或服务器证书确实无效。
  未产出任何 pin。"
    fi

    echo "$out" | grep -q "BEGIN CERTIFICATE" \
        || die "未拿到任何证书（握手成功但链为空）。未产出 pin。"

    echo "$out"
}

# ---------------------------------------------------------------------------
# split_chain CHAIN_TEXT TMPDIR
#   把 -showcerts 输出里的每个 PEM 块拆成独立文件，命名 cert-01.pem ...
# ---------------------------------------------------------------------------
split_chain() {
    local chain="$1" tmpdir="$2"
    echo "$chain" | awk -v outdir="$tmpdir" '
        /BEGIN CERTIFICATE/ { incert = 1; buf = "" }
        incert              { buf = buf $0 "\n" }
        /END CERTIFICATE/   {
            incert = 0
            n++
            file = sprintf("%s/cert-%02d.pem", outdir, n)
            printf "%s", buf > file
            close(file)
        }
    '
}

# ---------------------------------------------------------------------------
# self_test
#   不联网。生成自签证书，验证 pin_of 与协议文档 §3.1 定义的算法一致：
#   SPKI(SHA-256) → Base64，长度 44 字符（32 字节 → 44 base64 chars + '='）。
# ---------------------------------------------------------------------------
self_test() {
    echo "── 自检：pin 算法（不联网）────────────────────────────────"
    local tmpdir
    tmpdir="$(mktemp -d)"
    trap 'rm -rf "$tmpdir"' RETURN

    command -v openssl >/dev/null 2>&1 || die "openssl 不可用"

    openssl req -x509 -newkey rsa:2048 \
        -keyout "$tmpdir/key.pem" -out "$tmpdir/cert.pem" \
        -days 1 -nodes -subj "/CN=self-test.local" >/dev/null 2>&1 \
        || die "无法生成测试证书"

    local pin
    pin="$(pin_of "$tmpdir/cert.pem")"

    # 断言 1：非空
    [[ -n "$pin" ]] || die "pin 计算返回空"

    # 断言 2：Base64 长度。SHA-256 = 32 字节 → ceil(32/3)*4 = 44（含 1 个 '='）
    [[ ${#pin} -eq 44 ]] || die "pin 长度应为 44，实际 ${#pin}（$pin）"

    # 断言 3：仅含 Base64 字符集
    [[ "$pin" =~ ^[A-Za-z0-9+/]+=$ ]] || die "pin 含非 Base64 字符：$pin"

    # 断言 4：与独立实现（openssl 直接管道）结果一致
    local expected
    expected="$(openssl x509 -in "$tmpdir/cert.pem" -pubkey -noout \
        | openssl pkey -pubin -outform der \
        | openssl dgst -sha256 -binary \
        | openssl enc -base64)"
    [[ "$pin" == "$expected" ]] || die "两次计算结果不一致：$pin vs $expected"

    # 断言 5：不同密钥产生不同 pin（避免常量化错误）
    openssl req -x509 -newkey rsa:2048 \
        -keyout "$tmpdir/key2.pem" -out "$tmpdir/cert2.pem" \
        -days 1 -nodes -subj "/CN=self-test.local" >/dev/null 2>&1
    local pin2
    pin2="$(pin_of "$tmpdir/cert2.pem")"
    [[ "$pin" != "$pin2" ]] || die "不同密钥产生了相同 pin，算法可疑"

    echo "✓ 生成测试证书并计算 pin：$pin"
    echo "✓ 长度 44、Base64 字符集、可重复、随密钥变化——全部通过"

    # 断言 6-8：三级证书链的拆分与 pin 提取（对应主流程 split_chain 的行为）。
    #   主流程依赖第 1 张为叶子证书、第 2 张为中间 CA 来产出 primary/backup pin，
    #   此处用本地生成的 root → intermediate → leaf 锁定该顺序假设。
    echo ""
    echo "── 自检：证书链拆分（不联网）────────────────────────────"

    printf "basicConstraints=critical,CA:TRUE\n" > "$tmpdir/ca.ext"
    openssl req -x509 -newkey rsa:2048 -keyout "$tmpdir/root.key" \
        -out "$tmpdir/root.pem" -days 1 -nodes \
        -subj "/CN=Test Root CA" \
        -addext "basicConstraints=critical,CA:TRUE" >/dev/null 2>&1 \
        || die "生成 root CA 失败"
    openssl req -newkey rsa:2048 -keyout "$tmpdir/int.key" \
        -out "$tmpdir/int.csr" -nodes -subj "/CN=Test Intermediate CA" >/dev/null 2>&1
    openssl x509 -req -in "$tmpdir/int.csr" -CA "$tmpdir/root.pem" \
        -CAkey "$tmpdir/root.key" -out "$tmpdir/int.pem" -days 1 \
        -extfile "$tmpdir/ca.ext" -CAcreateserial >/dev/null 2>&1 \
        || die "签发 intermediate 失败"
    openssl req -newkey rsa:2048 -keyout "$tmpdir/leaf.key" \
        -out "$tmpdir/leaf.csr" -nodes -subj "/CN=leaf.test" >/dev/null 2>&1
    openssl x509 -req -in "$tmpdir/leaf.csr" -CA "$tmpdir/int.pem" \
        -CAkey "$tmpdir/int.key" -out "$tmpdir/leaf.pem" -days 1 \
        -CAcreateserial >/dev/null 2>&1 || die "签发 leaf 失败"

    # 模拟 `openssl s_client -showcerts` 的输出：leaf → intermediate → root
    local chain_text=""
    for c in leaf int root; do
        chain_text+=" 0 s:$(openssl x509 -in "$tmpdir/$c.pem" -noout -subject | sed 's/^subject=//')"$'\n'
        chain_text+="$(cat "$tmpdir/$c.pem")"$'\n'
    done

    local splitdir="$tmpdir/split"
    mkdir -p "$splitdir"
    split_chain "$chain_text" "$splitdir"

    local split_count
    split_count="$(ls "$splitdir"/cert-*.pem 2>/dev/null | wc -l)"
    [[ "$split_count" -eq 3 ]] \
        || die "链拆分应得 3 张证书，实际 $split_count"

    # 断言 6：第 1 张是叶子证书（主流程据此取 primary pin）
    local first_subject
    first_subject="$(subject_of "$splitdir/cert-01.pem")"
    [[ "$first_subject" == *"leaf.test"* ]] \
        || die "链首应为叶子证书，实际 subject=$first_subject"

    # 断言 7：第 2 张是中间 CA（主流程据此取 backup pin）
    local second_subject
    second_subject="$(subject_of "$splitdir/cert-02.pem")"
    [[ "$second_subject" == *"Intermediate"* ]] \
        || die "链次位应为中间 CA，实际 subject=$second_subject"

    # 断言 8：三级证书的 pin 互不相同
    local p1 p2 p3
    p1="$(pin_of "$splitdir/cert-01.pem")"
    p2="$(pin_of "$splitdir/cert-02.pem")"
    p3="$(pin_of "$splitdir/cert-03.pem")"
    [[ "$p1" != "$p2" && "$p2" != "$p3" && "$p1" != "$p3" ]] \
        || die "链上各证书 pin 应互不相同：leaf=$p1 int=$p2 root=$p3"

    echo "✓ 三级链（leaf → intermediate → root）拆分为 3 张证书"
    echo "✓ 顺序正确：#1=${p1:0:12}…（leaf）、#2=${p2:0:12}…（intermediate）"
    echo "✓ 各级 pin 互不相同——primary/backup pin 提取逻辑可用"
    echo ""
    echo "  说明：自检只验证 pin 计算算法，不验证网络与环境可信性。"
    echo "        真实 pin 必须在可信网络下运行本脚本获取。"
    echo "──────────────────────────────────────────────────────────"
}

# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------
main() {
    local host="${1:-$DEFAULT_HOST}"
    local port="${2:-$DEFAULT_PORT}"

    # 参数处理
    case "$host" in
        -h|--help)
            sed -n '2,50p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        --self-test)
            self_test
            exit 0
            ;;
        -*)
            echo "用法：bash scripts/fetch-tls-pins.sh [HOST] [PORT]" >&2
            echo "      bash scripts/fetch-tls-pins.sh --self-test" >&2
            exit 2
            ;;
    esac

    command -v openssl >/dev/null 2>&1 || die "需要 openssl，请先安装"
    command -v getent >/dev/null 2>&1 || warn "getent 不可用，跳过 DNS 检查"

    echo ""
    echo "══ 获取 $host 的 TLS 证书锁定 pin ══"
    echo ""

    check_environment "$host" || exit 1

    echo ""
    echo "── 抓取证书链（强制主机名 + CA 链校验）───────────────────"
    local chain
    chain="$(fetch_chain "$host" "$port")"
    echo "✓ TLS 握手成功且证书链校验通过"

    local tmpdir
    tmpdir="$(mktemp -d)"
    trap 'rm -rf "$tmpdir"' EXIT

    split_chain "$chain" "$tmpdir"

    local certs=()
    local f
    while IFS= read -r f; do
        certs+=("$f")
    done < <(ls "$tmpdir"/cert-*.pem 2>/dev/null | sort || true)

    [[ ${#certs[@]} -gt 0 ]] || die "证书链拆分失败"

    echo ""
    echo "── 证书链（请人工核对 subject / issuer 是否为真实身份）────"
    local i=0
    local leaf_pin="" issuer_pin=""
    for f in "${certs[@]}"; do
        i=$((i + 1))
        local pin sub iss dates
        pin="$(pin_of "$f")"
        sub="$(subject_of "$f")"
        iss="$(issuer_of "$f")"
        dates="$(dates_of "$f")"
        local role="中间 CA"
        [[ $i -eq 1 ]] && role="叶子证书"
        [[ "$sub" == "$iss" ]] && role="根 CA（不建议 pin，见文档 §3.2）"
        echo ""
        echo "  [$i] $role"
        echo "      subject : $sub"
        echo "      issuer  : $iss"
        echo "      有效期  : $dates"
        echo "      SPKI pin: sha256/$pin"
        if [[ $i -eq 1 ]]; then
            leaf_pin="$pin"
        elif [[ -z "$issuer_pin" ]]; then
            issuer_pin="$pin"
        fi
    done

    # 格式自校验：真实 pin 必须是 44 字符 Base64
    if [[ ${#leaf_pin} -ne 44 ]] || [[ ! "$leaf_pin" =~ ^[A-Za-z0-9+/]+=$ ]]; then
        die "叶子证书 pin 格式异常（长度 ${#leaf_pin}）：$leaf_pin"
    fi
    echo ""
    echo "✓ pin 格式自校验通过（44 字符 Base64）"

    # ---------------------------------------------------------------
    # 输出可直接粘贴的代码片段
    # ---------------------------------------------------------------
    echo ""
    echo "═══════════════════════════════════════════════════════════════"
    echo " 替换片段 — Android"
    echo " 文件: android/app/src/main/java/com/agentcontrolcenter/app/transport/http/CertificatePinnerFactory.kt"
    echo "═══════════════════════════════════════════════════════════════"
    cat <<EOF

        put("$host", listOf(
            "sha256/$leaf_pin",   // primary — 当前叶子证书公钥
EOF
    if [[ -n "$issuer_pin" ]]; then
        cat <<EOF
            "sha256/$issuer_pin"    // backup  — 中间 CA 公钥（leaf 轮换时仍有效）
EOF
    else
        cat <<EOF
            "sha256/REPLACE_WITH_BACKUP_PIN="    // backup  — 备用公钥（必填，见 doc §3.2）
EOF
    fi
    cat <<EOF
        ))
EOF

    echo ""
    echo "═══════════════════════════════════════════════════════════════"
    echo " 替换片段 — iOS"
    echo " 文件: ios/AgentControlCenter/Transport/TLSPinningDelegate.swift"
    echo "═══════════════════════════════════════════════════════════════"
    cat <<EOF

        "$host": [
            "sha256/$leaf_pin",   // primary — 当前叶子证书公钥
EOF
    if [[ -n "$issuer_pin" ]]; then
        cat <<EOF
            "sha256/$issuer_pin"    // backup  — 中间 CA 公钥（leaf 轮换时仍有效）
EOF
    else
        cat <<EOF
            "sha256/REPLACE_WITH_BACKUP_PIN="     // backup  — 备用公钥（必填，见 doc §3.2）
EOF
    fi
    cat <<EOF
        ]
EOF

    echo ""
    echo "═══════════════════════════════════════════════════════════════"
    echo " 后续步骤"
    echo "═══════════════════════════════════════════════════════════════"
    echo ""
    echo "  1. 把上面两段分别粘贴到 Android / iOS 的 PUBLIC_API_PINS 中，"
    echo "     双端必须完全一致（protocol/transport/tls-pinning.md 为单一事实来源）。"
    echo "  2. 同步更新 protocol/transport/tls-pinning.md 第 4 节的 pin 列表，"
    echo "     并记录获取日期与证书有效期。"
    echo "  3. 运行 bash scripts/check-tls-pins.sh 校验双端一致性与格式。"
    echo "  4. 冒烟验证：真机/模拟器上连接 $host 必须成功；"
    echo "     再用代理工具注入假证书，连接必须失败（确认锁定真的生效）。"
    echo ""
    if [[ -n "$issuer_pin" ]]; then
        echo "  注：backup pin 取自中间 CA 的公钥。服务端轮换叶子证书（同一 CA 签发）"
        echo "      时该 pin 仍然有效，可避免「一轮换就全网断连」——详见文档 §3.2。"
    else
        warn "证书链只有 ${#certs[@]} 张证书，未能提取中间 CA pin。"
        info "请手动获取备用 pin：仅填 leaf pin 时，服务端一换密钥对就会断连。"
    fi
    echo ""
}

# 仅在直接执行时运行 main，便于测试脚本 source 本文件调用 pin_of 等函数
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    main "$@"
fi
