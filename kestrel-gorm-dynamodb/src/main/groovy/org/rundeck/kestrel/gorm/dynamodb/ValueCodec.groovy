package org.rundeck.kestrel.gorm.dynamodb

import groovy.transform.CompileStatic
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.dynamodb.model.AttributeValue

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime

/**
 * Converts GORM native-entry values to DynamoDB attribute values and back. Decoding is driven by
 * the declared Java type, because DynamoDB numbers carry no type (a Date and a Long are both N).
 * Dates are epoch milliseconds, so range comparisons and sort order work on the stored form.
 */
@CompileStatic
class ValueCodec {

    /**
     * @param value native value
     * @return attribute value, or null when the attribute should be omitted (null values)
     */
    static AttributeValue encode(Object value) {
        if (value == null) {
            return null
        }
        if (value instanceof AttributeValue) {
            return (AttributeValue) value
        }
        if (value instanceof CharSequence) {
            return AttributeValue.fromS(value.toString())
        }
        if (value instanceof Boolean) {
            return AttributeValue.fromBool((Boolean) value)
        }
        if (value instanceof Number) {
            return AttributeValue.fromN(numberString((Number) value))
        }
        if (value instanceof Date) {
            return AttributeValue.fromN(Long.toString(((Date) value).time))
        }
        if (value instanceof Instant) {
            return AttributeValue.fromN(Long.toString(((Instant) value).toEpochMilli()))
        }
        if (value instanceof Enum) {
            return AttributeValue.fromS(((Enum) value).name())
        }
        if (value instanceof byte[]) {
            return AttributeValue.fromB(SdkBytes.fromByteArray((byte[]) value))
        }
        if (value instanceof Map) {
            Map<String, AttributeValue> m = [:]
            ((Map) value).each { k, v ->
                def av = encode(v)
                m.put(k.toString(), av ?: AttributeValue.fromNul(true))
            }
            return AttributeValue.fromM(m)
        }
        if (value instanceof Collection || value instanceof Object[]) {
            List<AttributeValue> l = []
            (value as Collection).each { v -> l << (encode(v) ?: AttributeValue.fromNul(true)) }
            return AttributeValue.fromL(l)
        }
        // UUID, Locale, TimeZone, URL, java.time local types, Currency...: their string form.
        return AttributeValue.fromS(value instanceof TimeZone ? ((TimeZone) value).ID : value.toString())
    }

    /**
     * @param av   stored attribute
     * @param type declared Java type, or null when unknown (returns the natural form)
     * @return decoded value
     */
    static Object decode(AttributeValue av, Class type) {
        if (av == null || Boolean.TRUE == av.nul()) {
            return null
        }
        if (av.s() != null) {
            return decodeString(av.s(), type)
        }
        if (av.n() != null) {
            return decodeNumber(av.n(), type)
        }
        if (av.bool() != null) {
            return av.bool()
        }
        if (av.b() != null) {
            return av.b().asByteArray()
        }
        if (av.hasM()) {
            Map<String, Object> m = new LinkedHashMap<>()
            av.m().each { k, v -> m.put(k, decode(v, null)) }
            return m
        }
        if (av.hasL()) {
            List l = av.l().collect { decode(it, null) }
            return type != null && Set.isAssignableFrom(type) ? new LinkedHashSet(l) : l
        }
        if (av.hasSs()) {
            return new LinkedHashSet<String>(av.ss())
        }
        return null
    }

    /** @return canonical decimal text for a number (no exponent, no trailing zeros for integrals) */
    static String numberString(Number n) {
        if (n instanceof BigDecimal) {
            return ((BigDecimal) n).toPlainString()
        }
        if (n instanceof Double || n instanceof Float) {
            return new BigDecimal(n.toString()).toPlainString()
        }
        return n.toString()
    }

    @SuppressWarnings('GrMethodMayBeStatic')
    private static Object decodeNumber(String n, Class type) {
        if (type == null || type == Object) {
            return n.contains('.') ? new BigDecimal(n) : Long.valueOf(n)
        }
        switch (type) {
            case Date: return new Date(Long.parseLong(n))
            case java.sql.Timestamp: return new java.sql.Timestamp(Long.parseLong(n))
            case Instant: return Instant.ofEpochMilli(Long.parseLong(n))
            case Long: case long: return Long.valueOf(n)
            case Integer: case int: return Integer.valueOf(n)
            case Short: case short: return Short.valueOf(n)
            case Byte: case byte: return Byte.valueOf(n)
            case Double: case double: return Double.valueOf(n)
            case Float: case float: return Float.valueOf(n)
            case BigDecimal: return new BigDecimal(n)
            case BigInteger: return new BigInteger(n)
            case String: return n
            case Boolean: case boolean: return n != '0'
            default: return n.contains('.') ? new BigDecimal(n) : Long.valueOf(n)
        }
    }

    private static Object decodeString(String s, Class type) {
        if (type == null || type == String || type == Object || CharSequence.isAssignableFrom(type)) {
            return s
        }
        if (type.isEnum()) {
            return Enum.valueOf((Class<Enum>) type, s)
        }
        switch (type) {
            case UUID: return UUID.fromString(s)
            case Locale: return Locale.forLanguageTag(s.replace('_', '-'))
            case TimeZone: return TimeZone.getTimeZone(s)
            case URL: return new URL(s)
            case URI: return URI.create(s)
            case Currency: return Currency.getInstance(s)
            case LocalDate: return LocalDate.parse(s)
            case LocalDateTime: return LocalDateTime.parse(s)
            case OffsetDateTime: return OffsetDateTime.parse(s)
            case ZonedDateTime: return ZonedDateTime.parse(s)
            case Long: case long: return Long.valueOf(s)
            case Integer: case int: return Integer.valueOf(s)
            case Boolean: case boolean: return Boolean.valueOf(s)
            default: return s
        }
    }

    /**
     * Key form of a value for the index table and for keys: entities and dates collapse to their
     * stored scalar so equality queries match what was indexed.
     */
    static String keyString(Object value) {
        def av = encode(value)
        if (av == null) {
            return null
        }
        if (av.s() != null) return av.s()
        if (av.n() != null) return av.n()
        if (av.bool() != null) return av.bool().toString()
        return av.toString()
    }
}
