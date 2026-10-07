package com.geoffchan.glucosewidget.wear

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueTest {
    private val item = QueuedItem("a1", "took 6 and a chicken burger", 1759871400000)

    @Test fun `queue list round-trips and tolerates junk`() {
        val items = listOf(item, QueuedItem("b2", "candy", 1759872000000))
        assertEquals(items, decodeQueue(encodeQueue(items)))
        assertEquals(emptyList<QueuedItem>(), decodeQueue(null))
        assertEquals(emptyList<QueuedItem>(), decodeQueue("garbage"))
    }

    @Test fun `queued message carries id, text and when she spoke`() {
        val o = JSONObject(encodeQueued(item))
        assertEquals("a1", o.getString("id"))
        assertEquals("took 6 and a chicken burger", o.getString("text"))
        assertEquals(1759871400000, o.getLong("spokenAtMs"))
    }

    @Test fun `ack settles only its own item, saved or duplicate`() {
        // exactly what the phone's encodeQueuedAck produces
        assertTrue(ackSettles(item, decodeQueuedAck("""{"id":"a1","ok":true,"error":null,"saved":2,"duplicate":false}""")))
        assertTrue(ackSettles(item, decodeQueuedAck("""{"id":"a1","ok":true,"error":null,"saved":0,"duplicate":true}""")))
        assertFalse(ackSettles(item, decodeQueuedAck("""{"id":"a1","ok":false,"error":"x","saved":0,"duplicate":false}""")))
        assertFalse(ackSettles(item, decodeQueuedAck("""{"id":"zz","ok":true,"error":null,"saved":1,"duplicate":false}""")))
    }
}
