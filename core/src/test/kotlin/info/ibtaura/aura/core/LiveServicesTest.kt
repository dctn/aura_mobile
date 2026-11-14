package info.ibtaura.aura.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LiveServicesTest {
    private val devText = File("../app/src/main/assets/aura-dev.json").readText()

    private fun refused(config: AppConfig): Boolean =
        runCatching { LiveServices.checked(config) }.exceptionOrNull().let { it is AuraError.Server && it.code == "bad_config" }

    @Test
    fun devConfigBuildsLiveServices() {
        val (api, auth) = LiveServices.make(devText, InMemoryTokenStore())
        assertTrue(api is LiveApi)
        assertTrue(auth is CognitoAuth)
    }

    @Test
    fun devConfigIsAndroidOnDevInUsWest1() {
        val config = AppConfig.decode(devText)
        assertEquals("dev", config.stage)
        assertEquals("us-west-1", config.region)
        assertEquals("android", config.platform)
        for (name in ServiceName.entries.filter { it != ServiceName.appleHealthData }) {
            assertTrue("missing ${name.raw}", config.baseURL(name) != null)
        }
    }

    @Test
    fun refusesProdAndApSouth1() {
        val dev = AppConfig.decode(devText)
        assertTrue(refused(dev.copy(stage = "prod")))
        assertTrue(refused(dev.copy(region = "ap-south-1")))
        assertTrue(refused(dev.copy(services = dev.services + ("task" to "https://x.execute-api.ap-south-1.amazonaws.com/"))))
        assertEquals(dev, LiveServices.checked(dev))
    }
}
