package com.ayongw.plugins.opencode.shared

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object LocalDateTimeSerializer : KSerializer<LocalDateTime> {
    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LocalDateTime", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalDateTime) {
        encoder.encodeString(value.format(formatter))
    }

    override fun deserialize(decoder: Decoder): LocalDateTime {
        return LocalDateTime.parse(decoder.decodeString(), formatter)
    }
}

/** 标记字段为非序列化 - 用于 @Transient 注解 */
@kotlin.AnnotationRetention(kotlin.AnnotationRetention.SOURCE)
@kotlin.AnnotationTarget(kotlin.AnnotationTarget.FIELD, kotlin.AnnotationTarget.PROPERTY_GETTER, kotlin.AnnotationTarget.PROPERTY_SETTER)
annotation class Transient