package com.tinyggrok.app.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitEnquiryTest {

    /** Everyday prompts; the old test sent 15 of these down the slow rail path. */
    private val ordinary = listOf(
        "Translate this from English to German: good morning",
        "How do I get my dog to stop barking at night?",
        "Convert 150 pounds from GBP to EUR",
        "What happened to the stock market from Monday to Friday?",
        "Summarise the article from the start to the end",
        "I want to go to the gym more often, any tips?",
        "How do I get Python to read a CSV file?",
        "Explain how to transfer money from Revolut to Wise",
        "What is the best way to get rid of mould in the bathroom",
        "Is there a delay in the Apple iPhone 18 release?",
        "Write an email to my landlord about the heating",
        "What time is it in Tokyo?",
        "Recipe for chicken curry for 4 people",
        "How do I get to sleep faster?",
        "What should I pack to go to Spain in October",
        "Compare the iPhone 17 to the Pixel 10",
        "Tell me the journey of the Beatles from Liverpool to fame",
        "Explain the difference from a Roth IRA to a traditional IRA",
        "Can you help me get my CV ready to send today",
        "How do I train a neural network in PyTorch?",
        "She trains at the gym every morning",
        "Which platform is best for a small online shop?",
        "What is the best radio station for jazz?",
        "Make me a revision timetable for my exams",
        "Tell me about Queen Victoria",
        "Who won the battle of Waterloo?",
        "Is YouTube down?",
        "Flight departures from Heathrow terminal 5"
    )

    /** Real transport questions, which must still get the rail playbook. */
    private val transit = listOf(
        "When is the next train from St Albans to London",
        "Is the Thameslink running today",
        "Trains from St Albans to St Pancras tonight",
        "Any delays on Thameslink?",
        "How do I get to Kings Cross from here",
        "Find me transport from my current location to Harpenden",
        "Live departures St Albans City",
        "Is there a rail strike tomorrow?",
        "What time is the last train to St Albans",
        "Bus times from St Albans to Hatfield",
        "Is the Elizabeth line delayed?",
        "Tube strike today?",
        "How do I get from Luton airport to St Albans",
        "Where is the nearest station?",
        "Which trains stop at Harpenden?",
        "Is the Abbey Line running this weekend"
    )

    @Test
    fun `everyday prompts are not treated as rail enquiries`() {
        val wrong = ordinary.filter { looksLikeTransitEnquiry(it) }
        assertTrue("sent down the rail path: $wrong", wrong.isEmpty())
    }

    @Test
    fun `transport questions still are`() {
        val missed = transit.filterNot { looksLikeTransitEnquiry(it) }
        assertTrue("missed: $missed", missed.isEmpty())
    }

    @Test
    fun `only prompts that depend on where you are wait for a GPS fix`() {
        assertTrue(wantsFreshLocation("What's the weather tomorrow"))
        assertTrue(wantsFreshLocation("Coffee shops near me"))
        assertTrue(wantsFreshLocation("Next train from here to London"))
        assertFalse(wantsFreshLocation("Translate this from English to German"))
        assertFalse(wantsFreshLocation("Explain quantum entanglement simply"))
        assertFalse(wantsFreshLocation(""))
    }
}
