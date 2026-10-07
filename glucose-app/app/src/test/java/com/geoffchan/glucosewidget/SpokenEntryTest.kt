package com.geoffchan.glucosewidget

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class SpokenEntryTest {
    private val now = LocalTime.of(17, 42)

    private fun parse(text: String, at: LocalTime = now) = parseSpoken(text, at).map { it.noteText() }

    @Test fun `took 6 units and having a chicken burger`() = assertEquals(
        listOf("dose: short-acting 6u @ 17:42", "event: chicken burger @ 17:42"),
        parse("just took 6 units and having a chicken burger"),
    )

    @Test fun `coffee and 19 and 4 - unlabelled pair splits long and short`() = assertEquals(
        listOf("event: coffee @ 10:31", "dose: long-acting 19u @ 10:31", "dose: short-acting 4u @ 10:31"),
        parse("coffee and 19 and 4", LocalTime.of(10, 31)),
    )

    @Test fun `stated time applies to the whole sentence`() = assertEquals(
        listOf("dose: short-acting 4u @ 10:30", "event: coffee @ 10:30"),
        parse("4 units short with my coffee at 10:30", LocalTime.of(11, 5)),
    )

    @Test fun `took seven after a pizza`() = assertEquals(
        listOf("event: half a pizza hut pizza @ 17:42", "dose: short-acting 7u @ 17:42"),
        parse("ate half a pizza hut pizza took seven"),
    )

    @Test fun `a single word`() = assertEquals(listOf("event: candy @ 17:42"), parse("candy"))

    @Test fun `exercise with a duration`() {
        assertEquals(listOf("event: 20 min walk @ 17:42"), parse("walked for 20 minutes"))
        assertEquals(listOf("event: 30 min walk @ 17:42"), parse("30 minute walk"))
        assertEquals(listOf("event: 60 min yoga @ 17:42"), parse("did yoga for an hour"))
        assertEquals(listOf("event: walk @ 17:42"), parse("went for a walk"))
    }

    @Test fun `relative times`() {
        assertEquals(listOf("dose: short-acting 2u @ 16:42"), parse("took 2 units an hour ago"))
        assertEquals(listOf("event: cookie @ 17:12"), parse("had a cookie half an hour ago"))
        assertEquals(listOf("event: juice @ 17:22"), parse("juice 20 minutes ago"))
        assertEquals(listOf("event: candy @ 17:37"), parse("candy a few minutes ago"))
    }

    @Test fun `number words with types`() = assertEquals(
        listOf("dose: long-acting 19u @ 17:42", "dose: short-acting 4u @ 17:42"),
        parse("nineteen long four short"),
    )

    @Test fun `long lasting and brand names`() {
        assertEquals(listOf("dose: long-acting 20u @ 17:42"), parse("20 units of lantus"))
        assertEquals(listOf("dose: long-acting 19u @ 17:42"), parse("19 units long lasting"))
        assertEquals(listOf("dose: short-acting 5u @ 17:42"), parse("five units humalog"))
        assertEquals(listOf("dose: short-acting 3u @ 17:42"), parse("short acting 3"))
        assertEquals(listOf("dose: short-acting 4u @ 17:42"), parse("4u"))
    }

    @Test fun `plus and digits`() = assertEquals(
        listOf("dose: long-acting 19u @ 10:30", "dose: short-acting 4u @ 10:30"),
        parse("19+4 this morning"),
    )

    @Test fun `a lone mid-size dose is short unless it looks like the long one`() {
        assertEquals(listOf("dose: short-acting 12u @ 17:42"), parse("took 12"))
        assertEquals(listOf("dose: long-acting 19u @ 17:42"), parse("took 19"))
        // with a labelled long already, another 15-30 is short
        assertEquals(listOf("dose: long-acting 19u @ 17:42", "dose: short-acting 16u @ 17:42"), parse("19 long and 16"))
    }

    @Test fun `quantities in food are not doses`() {
        assertEquals(listOf("event: 2 cookies @ 17:42"), parse("had 2 cookies"))
        assertEquals(listOf("event: 3 slices of pizza @ 17:42", "dose: short-acting 6u @ 17:42"), parse("3 slices of pizza and 6 units"))
    }

    @Test fun `clock times with and without am-pm`() {
        assertEquals(listOf("event: lunch @ 13:00"), parse("lunch at 1", LocalTime.of(14, 0)))
        assertEquals(listOf("dose: short-acting 6u @ 17:30"), parse("took 6 at 5:30"))
        assertEquals(listOf("event: pizza @ 18:00"), parse("pizza at 6 pm", LocalTime.of(9, 0)))
        assertEquals(listOf("event: pizza @ 18:00"), parse("pizza at 6 p.m."))
        assertEquals(listOf("event: sandwich @ 12:00"), parse("sandwich at noon"))
        assertEquals(listOf("event: coffee @ 10:30"), parse("coffee at 10 30", LocalTime.of(11, 0)))
    }

    @Test fun `about N units is a dose, not a time`() =
        assertEquals(listOf("dose: short-acting 5u @ 17:42"), parse("took about 5 units"))

    @Test fun `several times - untimed items take the next stated time`() = assertEquals(
        listOf("event: coffee @ 10:30", "dose: short-acting 4u @ 10:30", "event: pizza @ 18:00", "dose: short-acting 7u @ 18:00"),
        parse("coffee and 4 units at 10:30, then pizza at 6 and took 7", LocalTime.of(19, 0)),
    )

    @Test fun `meal phrases and fillers`() {
        assertEquals(listOf("event: coffee @ 17:42"), parse("I had coffee for breakfast"))
        assertEquals(listOf("event: dinner @ 17:42", "dose: short-acting 6u @ 17:42"), parse("had dinner and took 6"))
        assertEquals(listOf("event: bagel with cream cheese @ 17:42"), parse("I just ate a bagel with cream cheese"))
        assertEquals(listOf("event: mac and cheese @ 17:42"), parse("mac and cheese"))
    }

    @Test fun `nothing usable`() {
        assertEquals(emptyList<String>(), parse(""))
        assertEquals(emptyList<String>(), parse("um just"))
    }

    @Test fun `ago past midnight clamps to midnight`() =
        assertEquals(listOf("event: candy @ 00:00"), parse("candy 2 hours ago", LocalTime.of(0, 30)))
}
