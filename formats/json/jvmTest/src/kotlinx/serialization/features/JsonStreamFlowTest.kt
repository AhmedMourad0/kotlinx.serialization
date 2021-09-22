/*
 * Copyright 2017-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.serialization.features

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import kotlinx.serialization.test.assertFailsWithMessage
import org.junit.Test
import java.io.*
import kotlin.test.*

class JsonStreamFlowTest {
    val json = Json {}

    suspend inline fun <reified T> Flow<T>.writeToStream(os: OutputStream) {
        collect {
            json.encodeToStream(it, os)
        }
    }

    suspend inline fun <reified T> Json.readFromStream(iss: InputStream): Flow<T> = flow {
        val iter = iterateOverStream(iss)
        val serial = serializer<T>()
        while (iter.hasNext()) {
            emit(iter.next(serial))
        }
    }.flowOn(Dispatchers.IO)

    val inputString = """{"data":"a"}{"data":"b"}{"data":"c"}"""
    val inputList = listOf(StringData("a"), StringData("b"), StringData("c"))

    @Test
    fun testEncodeSeveralItems() {
        val items = inputList
        val os = ByteArrayOutputStream()
        runBlocking {
            val f = flow<StringData> { items.forEach { emit(it) } }
            f.writeToStream(os)
        }

        assertEquals(inputString, os.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun testDecodeSeveralItems() {
        val ins = ByteArrayInputStream(inputString.encodeToByteArray())
        assertFailsWithMessage<SerializationException>("EOF") {
            json.decodeFromStream<StringData>(ins)
        }
    }

    inline fun <reified T> JsonIterator.assertNext(expected: T) {
        assertTrue(hasNext())
        assertEquals(expected, next(serializer()))
    }

    @Test
    fun testIterateSeveralItems() {

        val ins = ByteArrayInputStream(inputString.encodeToByteArray())
        val iter = json.iterateOverStream(ins)
        iter.assertNext(StringData("a"))
        iter.assertNext(StringData("b"))
        iter.assertNext(StringData("c"))
        assertFalse(iter.hasNext())
        assertFailsWithMessage<SerializationException>("EOF") {
            iter.next(StringData.serializer())
        }
    }

    @Test
    fun testDecodeToSequence() {
        val ins = ByteArrayInputStream(inputString.encodeToByteArray())
        assertEquals(inputList, json.decodeToSequence(ins, StringData.serializer()).toList())
    }

    @Test
    fun testDecodeAsFlow() {
        val ins = ByteArrayInputStream(inputString.encodeToByteArray())
        val list = runBlocking {
            buildList { json.readFromStream<StringData>(ins).toCollection(this) }
        }
        assertEquals(inputList, list)
    }

    @Test
    fun testDecodeDifferentItems() {
        val input = """{"data":"a"}{"intV":10}null{"data":"b"}"""
        val ins = ByteArrayInputStream(input.encodeToByteArray())
        val iter = json.iterateOverStream(ins)
        iter.assertNext(StringData("a"))
        iter.assertNext(IntData(10))
        iter.assertNext<String?>(null)
        iter.assertNext(StringData("b"))
        assertFalse(iter.hasNext())
    }

    @Test
    fun testItemsSeparatedByWs() {
        val input = "{\"data\":\"a\"}   {\"data\":\"b\"}\n\t{\"data\":\"c\"}"
        val ins = ByteArrayInputStream(input.encodeToByteArray())
        assertEquals(inputList, json.decodeToSequence(ins, StringData.serializer()).toList())
    }

}
