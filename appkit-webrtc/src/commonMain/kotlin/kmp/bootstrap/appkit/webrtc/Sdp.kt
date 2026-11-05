package kmp.bootstrap.appkit.webrtc

/*
 * Công cụ đọc/sửa SDP thuần Kotlin (test được trên JVM). Dùng khi:
 *  - ép thứ tự codec video, ví dụ H.264 trước rồi VP8:   SdpTools.preferVideoCodecs(offer.sdp, listOf("H264", "VP8"))
 *  - chỉ giữ một profile H.264:                          SdpTools.keepH264Profile(sdp, "42e01f", packetizationMode = 1)
 *  - từ chối SDP không có DTLS:                          SdpTools.hasDtlsFingerprint(sdp)
 *  - biết hai bên đã chốt codec nào:                     SdpTools.negotiatedVideoCodec(answer.sdp)
 * Giữ nguyên kiểu xuống dòng của SDP gốc (\r\n hoặc \n).
 */
object SdpTools {
    private val helperCodecs = setOf("RTX", "RED", "ULPFEC", "FLEXFEC-03")

    /** true khi có `a=fingerprint:sha-256` ở mức phiên, hoặc ở MỌI phần `m=`. */
    fun hasDtlsFingerprint(sdp: String, algorithm: String = "sha-256"): Boolean {
        val s = split(sdp)
        val prefix = "a=fingerprint:$algorithm "
        if (s.session.any { it.startsWith(prefix, ignoreCase = true) }) return true
        return s.media.isNotEmpty() && s.media.all { m -> m.any { it.startsWith(prefix, ignoreCase = true) } }
    }

    /** Tên codec (chữ hoa) của phần `m=<kind>` đầu tiên, theo thứ tự ưu tiên trong dòng `m=`, bỏ codec phụ (rtx, red, fec). */
    fun codecs(sdp: String, kind: String = "video"): List<String> {
        val m = split(sdp).media.firstOrNull { it.first().startsWith("m=$kind ") } ?: return emptyList()
        val names = rtpmap(m)
        return payloads(m.first()).mapNotNull { names[it] }.filter { it !in helperCodecs }.distinct()
    }

    /** Codec video đầu tiên trong answer = codec hai bên dùng. null nếu không có video. */
    fun negotiatedVideoCodec(answerSdp: String): String? = codecs(answerSdp, "video").firstOrNull()

    /** Hướng (`sendrecv`, `sendonly`, `recvonly`, `inactive`) của phần `m=<kind>` đầu tiên. */
    fun direction(sdp: String, kind: String): String? {
        val m = split(sdp).media.firstOrNull { it.first().startsWith("m=$kind ") } ?: return null
        return m.firstOrNull { it in setOf("a=sendrecv", "a=sendonly", "a=recvonly", "a=inactive") }?.removePrefix("a=") ?: "sendrecv"
    }

    /**
     * Đưa các codec trong [preferred] (theo thứ tự đó) lên đầu dòng `m=video`; payload rtx đi theo ngay sau codec chính của nó.
     * Codec khác giữ nguyên thứ tự ở sau. [dropOthers] = true thì xoá hẳn codec không nằm trong [preferred]
     * (cùng các dòng rtpmap/fmtp/rtcp-fb của chúng); codec phụ red/ulpfec cũng bị xoá khi đó.
     */
    fun preferVideoCodecs(sdp: String, preferred: List<String>, dropOthers: Boolean = false): String =
        rewriteMedia(sdp, "video") { m ->
            val names = rtpmap(m)
            val apt = rtxApt(m)
            val pts = payloads(m.first())
            val wanted = preferred.map { it.uppercase() }
            val head = mutableListOf<String>()
            for (name in wanted) for (pt in pts) if (names[pt] == name) {
                head += pt
                head += pts.filter { apt[it] == pt }
            }
            val rest = pts.filter { it !in head }
            val keep = if (dropOthers) head else head + rest
            reorder(m, keep)
        }

    /** Chỉ giữ các payload H.264 có `profile-level-id` và `packetization-mode` cho trước (kèm rtx của chúng). Codec khác không đụng. */
    fun keepH264Profile(sdp: String, profileLevelId: String, packetizationMode: Int = 1): String =
        rewriteMedia(sdp, "video") { m ->
            val names = rtpmap(m)
            val apt = rtxApt(m)
            val fmtp = fmtp(m)
            val bad = payloads(m.first()).filter { pt ->
                names[pt] == "H264" && run {
                    val p = fmtp[pt].orEmpty()
                    !p.contains("profile-level-id=$profileLevelId", ignoreCase = true) || !p.contains("packetization-mode=$packetizationMode")
                }
            }.toSet()
            val keep = payloads(m.first()).filter { it !in bad && apt[it] !in bad }
            reorder(m, keep)
        }

    // ------------------------------------------------------------------ bên trong

    private class Split(val session: List<String>, val media: List<List<String>>, val eol: String, val trailingEol: Boolean)

    private fun split(sdp: String): Split {
        val eol = if (sdp.contains("\r\n")) "\r\n" else "\n"
        val trailing = sdp.endsWith(eol)
        val lines = sdp.split(eol).let { if (trailing) it.dropLast(1) else it }
        val session = mutableListOf<String>()
        val media = mutableListOf<MutableList<String>>()
        for (l in lines) if (l.startsWith("m=")) media += mutableListOf(l) else (media.lastOrNull() ?: session) += l
        return Split(session, media, eol, trailing)
    }

    private fun join(s: Split, media: List<List<String>>): String {
        val all = s.session + media.flatten()
        return all.joinToString(s.eol) + if (s.trailingEol) s.eol else ""
    }

    private fun rewriteMedia(sdp: String, kind: String, f: (List<String>) -> List<String>): String {
        val s = split(sdp)
        var done = false
        val media = s.media.map { m -> if (!done && m.first().startsWith("m=$kind ")) { done = true; f(m) } else m }
        return join(s, media)
    }

    private fun payloads(mLine: String) = mLine.split(" ").drop(3)

    private fun rtpmap(m: List<String>): Map<String, String> = m.mapNotNull { l ->
        if (!l.startsWith("a=rtpmap:")) return@mapNotNull null
        val pt = l.removePrefix("a=rtpmap:").substringBefore(" ")
        pt to l.substringAfter(" ").substringBefore("/").uppercase()
    }.toMap()

    private fun fmtp(m: List<String>): Map<String, String> = m.mapNotNull { l ->
        if (!l.startsWith("a=fmtp:")) null else l.removePrefix("a=fmtp:").substringBefore(" ") to l.substringAfter(" ")
    }.toMap()

    /** rtx payload → payload chính (`apt=`). */
    private fun rtxApt(m: List<String>): Map<String, String> = fmtp(m).mapNotNull { (pt, p) ->
        Regex("apt=(\\d+)").find(p)?.groupValues?.get(1)?.let { pt to it }
    }.toMap()

    /** Viết lại dòng m= với [keep], bỏ các dòng a=rtpmap/fmtp/rtcp-fb của payload bị loại. */
    private fun reorder(m: List<String>, keep: List<String>): List<String> {
        val parts = m.first().split(" ")
        val removed = payloads(m.first()).toSet() - keep.toSet()
        val head = (parts.take(3) + keep).joinToString(" ")
        val body = m.drop(1).filter { l ->
            val pt = when {
                l.startsWith("a=rtpmap:") -> l.removePrefix("a=rtpmap:").substringBefore(" ")
                l.startsWith("a=fmtp:") -> l.removePrefix("a=fmtp:").substringBefore(" ")
                l.startsWith("a=rtcp-fb:") -> l.removePrefix("a=rtcp-fb:").substringBefore(" ")
                else -> null
            }
            pt == null || pt !in removed
        }
        return listOf(head) + body
    }
}
