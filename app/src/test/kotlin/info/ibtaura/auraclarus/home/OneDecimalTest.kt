package info.ibtaura.auraclarus.home

import kotlin.test.Test
import kotlin.test.assertEquals

class OneDecimalTest {
    @Test
    fun capacityShowsOneDecimal() {
        assertEquals("87.8", oneDecimal(87.76))
        assertEquals("88.0", oneDecimal(88.0))
        assertEquals("64.0", oneDecimal(63.97))
    }
}
