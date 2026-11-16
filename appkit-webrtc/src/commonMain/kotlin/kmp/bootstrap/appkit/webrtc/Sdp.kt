package kmp.bootstrap.appkit.webrtc

/*
 * Xử lý SDP dạng chữ (RFC 8866), không cần thư viện WebRTC: kiểm DTLS, sắp lại codec, giới hạn bitrate, che khi ghi log.
 * Chỉ sắp lại hay thêm dòng, không bao giờ xoá codec: bên kia vẫn chọn được nếu không hỗ trợ lựa chọn ưu tiên.
 */
object Sdp {
    /** Có `a=fingerprint:` không rỗng (DTLS-SRTP). Thiếu thì media không được mã hoá đúng chuẩn: phải từ chối. Giống hàm của server Go. */
    fun hasDtlsFingerprint(sdp: String): Boolean =
        lines(sdp).any { it.startsWith("a=fingerprint:") && it.length > "a=fingerprint:".length }

    /** Giá trị `a=setup:` đầu tiên (`actpass`, `active`, `passive`) hoặc `null`. */
    fun setupRole(sdp: String): String? = lines(sdp).firstOrNull { it.startsWith("a=setup:") }?.substringAfter(':')

    /**
     * Đưa codec [codec] (vd "H264") lên đầu danh sách của phần `m=<kind>`, kèm gói RTX của nó. [fmtp] lọc thêm theo tham số
     * (vd chỉ lấy H.264 Constrained Baseline: `{ "profile-level-id=42e01f" in it }`). Không có codec đó → trả nguyên văn.
     */
    fun preferCodec(sdp: String, codec: String, kind: String = "video", fmtp: (String) -> Boolean = { true }): String =
        editSection(sdp, kind) { section ->
            val m = section[0].split(' ')
            if (m.size < 4) return@editSection section
            val names = rtpmaps(section)
            val params = section.filter { it.startsWith("a=fmtp:") }.associate { it.substringAfter("a=fmtp:").substringBefore(' ') to it.substringAfter(' ', "") }
            val chosen = names.filter { (pt, n) -> n.equals(codec, ignoreCase = true) && fmtp(params[pt] ?: "") }.keys
            if (chosen.isEmpty()) return@editSection section
            val rtx = names.filter { (pt, n) -> n.equals("rtx", true) && params[pt]?.let { p -> Regex("apt=(\\d+)").find(p)?.groupValues?.get(1) } in chosen }.keys
            val front = m.drop(3).filter { it in chosen || it in rtx }
            listOf((m.take(3) + front + m.drop(3).filterNot { it in front }).joinToString(" ")) + section.drop(1)
        }

    /** Giới hạn băng thông của phần `m=<kind>`: thay `b=AS`/`b=TIAS` cũ bằng [kbps] (đặt sau dòng `c=`, như RFC yêu cầu). */
    fun limitBitrate(sdp: String, kbps: Int, kind: String = "video"): String {
        require(kbps > 0) { "kbps must be positive" }
        return editSection(sdp, kind) { section ->
            val kept = section.filterNot { it.startsWith("b=AS:") || it.startsWith("b=TIAS:") }
            val at = kept.indexOfFirst { it.startsWith("c=") }.let { if (it < 0) 0 else it }
            kept.take(at + 1) + listOf("b=AS:$kbps", "b=TIAS:${kbps * 1000L}") + kept.drop(at + 1)
        }
    }

    /**
     * Bản an toàn để ghi log: giữ dòng `m=`, codec (`a=rtpmap`), hướng media, `a=setup`, và mỗi candidate chỉ còn giao thức + loại.
     * Bỏ địa chỉ IP, cổng của candidate, ufrag/pwd, dấu vân tay.
     */
    fun redact(sdp: String): String = lines(sdp).mapNotNull { line ->
        when {
            line.startsWith("m=") || line.startsWith("a=rtpmap:") || line.startsWith("a=setup:") || line.startsWith("a=mid:") -> line
            line in setOf("a=sendrecv", "a=sendonly", "a=recvonly", "a=inactive") -> line
            line.startsWith("a=candidate:") -> IceCandidateInfo.parse(line.removePrefix("a="))?.let { "a=candidate:${it.protocol} typ ${it.type}" }
            line.startsWith("a=fingerprint:") -> "a=fingerprint:${line.substringAfter(':').substringBefore(' ')} <redacted>"
            else -> null
        }
    }.joinToString("\r\n")

    // ------------------------------------------------------------ nội bộ

    private fun lines(sdp: String) = sdp.split("\n").map { it.trimEnd('\r') }.filter { it.isNotEmpty() }

    private fun rtpmaps(section: List<String>): Map<String, String> = section.filter { it.startsWith("a=rtpmap:") }
        .associate { it.substringAfter("a=rtpmap:").substringBefore(' ') to it.substringAfter(' ').substringBefore('/') }

    /** Sửa phần media ĐẦU TIÊN thuộc [kind]; giữ kiểu xuống dòng của SDP gốc (\r\n theo chuẩn). */
    private fun editSection(sdp: String, kind: String, edit: (List<String>) -> List<String>): String {
        val eol = if ("\r\n" in sdp) "\r\n" else "\n"
        val all = lines(sdp)
        val start = all.indexOfFirst { it.startsWith("m=$kind ") }
        if (start < 0) return sdp
        val end = all.withIndex().indexOfFirst { (i, l) -> i > start && l.startsWith("m=") }.let { if (it < 0) all.size else it }
        val out = all.take(start) + edit(all.subList(start, end)) + all.drop(end)
        return out.joinToString(eol) + eol
    }
}

/** Phần hữu ích của một dòng candidate ICE (RFC 8839): `candidate:<f> <c> <proto> <prio> <ip> <port> typ <type> …`. */
data class IceCandidateInfo(val protocol: String, val address: String, val port: Int, val type: String, val tcpType: String?) {
    /** Đi vòng qua TURN. */
    val isRelay: Boolean get() = type == "relay"

    companion object {
        fun parse(candidate: String): IceCandidateInfo? {
            val p = candidate.removePrefix("a=").removePrefix("candidate:").trim().split(Regex("\\s+"))
            if (p.size < 8 || p[6] != "typ") return null
            val port = p[5].toIntOrNull() ?: return null
            val tcp = p.indexOf("tcptype").takeIf { it >= 0 && it + 1 < p.size }?.let { p[it + 1] }
            return IceCandidateInfo(p[2].lowercase(), p[4], port, p[7], tcp)
        }
    }
}
