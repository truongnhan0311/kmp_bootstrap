package kmp.bootstrap.appkit.iot

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Data point (DP) kiểu Tuya: mỗi tính năng của thiết bị là một mã số (`"101"`) có kiểu cố định. Thiết bị báo `{"dps":{"101":true}}`,
 * app ra lệnh cùng dạng. DpSchema kiểm kiểu và giới hạn TRƯỚC khi dùng hay gửi, để một giá trị sai không làm hỏng màn hình
 * hay đến được thiết bị. Danh sách DP cụ thể (101 đèn, 103 số người xem…) là của app, không nằm trong kit.
 */
sealed interface DpType {
    data object Bool : DpType
    data class Integer(val min: Long, val max: Long, val step: Long = 1) : DpType {
        init { require(min <= max && step > 0) { "need min <= max and step > 0" } }
    }
    data class Enum(val values: List<String>) : DpType {
        init { require(values.isNotEmpty()) { "enum needs at least one value" } }
    }
    data class Text(val maxLength: Int) : DpType
}

/** [writable] = app được phép ra lệnh đổi DP này; `false` = chỉ thiết bị báo (vd nhiệt độ). */
data class DpSpec(val id: String, val code: String, val type: DpType, val writable: Boolean = true)

sealed interface DpValue {
    data class Bool(val value: Boolean) : DpValue
    data class Integer(val value: Long) : DpValue
    data class Enum(val value: String) : DpValue
    data class Text(val value: String) : DpValue
}

data class DpError(val id: String, val reason: String)

sealed interface DpResult {
    /** Giá trị hợp lệ theo `id`. */
    data class Ok(val values: Map<String, DpValue>) : DpResult
    data class Invalid(val errors: List<DpError>) : DpResult
}

class DpSchema(specs: List<DpSpec>) {
    private val byId = specs.associateBy { it.id }
    private val byCode = specs.associateBy { it.code }

    init {
        require(byId.size == specs.size) { "duplicate dp id" }
        require(byCode.size == specs.size) { "duplicate dp code" }
    }

    fun spec(idOrCode: String): DpSpec? = byId[idOrCode] ?: byCode[idOrCode]

    /**
     * Kiểm một khối `dps`. Báo cáo từ thiết bị ([forWrite] = false): DP lạ bị BỎ QUA (thiết bị mới hơn app vẫn chạy).
     * Lệnh gửi đi ([forWrite] = true): DP lạ hoặc chỉ-đọc là LỖI. Có lỗi nào thì trả [DpResult.Invalid] với đủ mọi lỗi.
     */
    fun validate(dps: JsonObject, forWrite: Boolean = false): DpResult {
        val out = LinkedHashMap<String, DpValue>()
        val errors = mutableListOf<DpError>()
        for ((id, raw) in dps) {
            val spec = byId[id]
            if (spec == null) { if (forWrite) errors += DpError(id, "unknown dp"); continue }
            if (forWrite && !spec.writable) { errors += DpError(id, "dp is read-only"); continue }
            when (val r = parse(spec.type, raw)) {
                is DpValue -> out[id] = r
                is String -> errors += DpError(id, r)
            }
        }
        return if (errors.isEmpty()) DpResult.Ok(out) else DpResult.Invalid(errors)
    }

    /** Dựng khối `dps` cho một lệnh từ cặp (mã hoặc tên, giá trị Kotlin), có kiểm: sai thì ném [IllegalArgumentException]. */
    fun command(vararg values: Pair<String, Any>): JsonObject {
        val dps = values.associate { (key, v) ->
            val spec = requireNotNull(spec(key)) { "unknown dp $key" }
            spec.id to when (v) {
                is Boolean -> JsonPrimitive(v)
                is Number -> JsonPrimitive(v.toLong())
                is String -> JsonPrimitive(v)
                else -> throw IllegalArgumentException("unsupported value type for dp $key")
            }
        }
        val checked = validate(JsonObject(dps), forWrite = true)
        require(checked is DpResult.Ok) { "invalid command: ${(checked as DpResult.Invalid).errors}" }
        return JsonObject(dps)
    }

    /** Trả DpValue, hoặc chuỗi lý do lỗi. */
    private fun parse(type: DpType, raw: JsonElement): Any {
        val p = raw as? JsonPrimitive ?: return "expected a primitive value"
        return when (type) {
            DpType.Bool -> p.takeIf { !it.isString }?.booleanOrNull?.let { DpValue.Bool(it) } ?: "expected boolean"
            is DpType.Integer -> {
                val n = p.takeIf { !it.isString }?.longOrNull ?: return "expected integer"
                when {
                    n < type.min || n > type.max -> "out of range ${type.min}..${type.max}"
                    (n - type.min) % type.step != 0L -> "not a multiple of step ${type.step}"
                    else -> DpValue.Integer(n)
                }
            }
            is DpType.Enum -> if (p.isString && p.content in type.values) DpValue.Enum(p.content) else "expected one of ${type.values}"
            is DpType.Text -> when {
                !p.isString -> "expected string"
                p.content.length > type.maxLength -> "longer than ${type.maxLength}"
                else -> DpValue.Text(p.content)
            }
        }
    }
}
