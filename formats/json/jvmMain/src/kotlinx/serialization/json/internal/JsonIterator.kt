/*
 * Copyright 2017-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

@file:Suppress("FunctionName")

package kotlinx.serialization.json.internal

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.*

internal fun <T> JsonIterator(
    mode: LazyStreamingFormat,
    json: Json,
    lexer: ReaderJsonLexer,
    deserializer: DeserializationStrategy<T>
): Iterator<T> = when (lexer.determineFormat(mode)) {
    LazyStreamingFormat.WHITESPACE_SEPARATED -> JsonIteratorWsSeparated(
        json,
        lexer,
        deserializer
    ) // Can be many WS-separated independent arrays
    LazyStreamingFormat.ARRAY_WRAPPED -> JsonIteratorArrayWrapped(
        json,
        lexer,
        deserializer
    )
    LazyStreamingFormat.AUTO_DETECT -> error("AbstractJsonLexer.determineFormat must be called beforehand.")
}


private fun AbstractJsonLexer.determineFormat(suggested: LazyStreamingFormat): LazyStreamingFormat = when (suggested) {
    LazyStreamingFormat.WHITESPACE_SEPARATED ->
        LazyStreamingFormat.WHITESPACE_SEPARATED // do not call consumeStartArray here so we don't confuse parser with stream of lists
    LazyStreamingFormat.ARRAY_WRAPPED ->
        if (tryConsumeStartArray()) LazyStreamingFormat.ARRAY_WRAPPED
        else throw JsonDecodingException("Expected array start")
    LazyStreamingFormat.AUTO_DETECT ->
        if (tryConsumeStartArray()) LazyStreamingFormat.ARRAY_WRAPPED
        else LazyStreamingFormat.WHITESPACE_SEPARATED
}

private fun AbstractJsonLexer.tryConsumeStartArray(): Boolean {
    if (peekNextToken() == TC_BEGIN_LIST) {
        consumeNextToken(TC_BEGIN_LIST)
        return true
    }
    return false
}

private class JsonIteratorWsSeparated<T>(
    private val json: Json,
    private val lexer: ReaderJsonLexer,
    private val deserializer: DeserializationStrategy<T>
) : Iterator<T> {
    override fun next(): T =
        StreamingJsonDecoder(json, WriteMode.OBJ, lexer, deserializer.descriptor)
            .decodeSerializableValue(deserializer)

    override fun hasNext(): Boolean = lexer.isNotEof()
}

private class JsonIteratorArrayWrapped<T>(
    private val json: Json,
    private val lexer: ReaderJsonLexer,
    private val deserializer: DeserializationStrategy<T>
) : Iterator<T> {
    private var first = true

    override fun next(): T {
        if (first) {
            first = false
        } else {
            lexer.consumeNextToken(COMMA)
        }
        val input = StreamingJsonDecoder(json, WriteMode.OBJ, lexer, deserializer.descriptor)
        return input.decodeSerializableValue(deserializer)
    }

    // todo: should we check comma here?
    override fun hasNext(): Boolean {
        if (lexer.peekNextToken() == TC_END_LIST) {
            lexer.consumeNextToken(TC_END_LIST)
            if (lexer.isNotEof()) {
                // allow unclosed? depend on isLenient?
                if (lexer.peekNextToken() == TC_BEGIN_LIST) throw JsonDecodingException("Looks like you need another mode")
                throw JsonDecodingException("Dangling data after last bracket")
            } // todo: replace with lexer.fail?
            return false
        }
        if (!lexer.isNotEof()) throw JsonDecodingException("Unexpected EOF: no closing bracket")
        return true
    }
}
