package app.familygem.geni.sync

import app.familygem.geni.FakeTreeHost
import app.familygem.geni.api.GeniClient
import app.familygem.geni.api.RateLimiter
import app.familygem.geni.auth.AuthResult
import app.familygem.geni.auth.InMemoryTokenStore
import app.familygem.geni.map.GeniMapper
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.folg.gedcom.model.ChildRef
import org.folg.gedcom.model.Family
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.model.Person
import org.folg.gedcom.model.SpouseRef
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UploaderTest {

    private val server = MockWebServer()
    private val tokens = InMemoryTokenStore().apply { save(AuthResult.Success("abc", Long.MAX_VALUE)) }
    private var failLinkSave = false
    private var beforeSnapshotWrite: suspend () -> Unit = {}
    private val store = object : InMemorySyncStore() {
        override suspend fun updateSnapshot(treeId: Int, geniId: String, expected: String?, snapshot: String, geniUpdatedAt: String?): Boolean {
            beforeSnapshotWrite()
            return super.updateSnapshot(treeId, geniId, expected, snapshot, geniUpdatedAt)
        }
        override suspend fun saveLinks(links: List<GeniLink>) {
            if (failLinkSave) error("disk full")
            super.saveLinks(links)
        }
    }
    private var editHook: () -> Unit = {}
    private val host = object : FakeTreeHost() {
        override suspend fun <T> edit(treeId: Int, block: (Gedcom) -> T): T {
            editHook()
            return super.edit(treeId, block)
        }
    }

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun TestScope.uploader() = Uploader(
        GeniClient(server.url("/api/"), tokens, RateLimiter { testScheduler.currentTime }),
        store, host, now = { 7_000L }
    )

    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()
    private fun error(code: Int, body: String = "") = MockResponse.Builder().code(code).body(body).build()
    private fun link(geniId: String, gedcomId: String, snapshot: String? = "{}") = GeniLink(1, geniId, gedcomId, true, "1", snapshot, 5, 99)

    /** I1 and I2 (Geni couple, family F1 = union-10106), I3 new child of F1; I5 new parent of I1 in F2; I8 new child of F2. */
    private fun tree() = Gedcom().apply {
        fun person(id: String, geniId: String?) = Person().also { it.id = id; if (geniId != null) GeniMapper.setGeniId(it, geniId); addPerson(it) }
        person("I1", "profile-36430"); person("I2", "profile-36433"); person("I3", null); person("I5", null); person("I8", null)
        addFamily(Family().apply {
            id = "F1"; GeniMapper.setGeniId(this, "union-10106")
            addHusband(SpouseRef().apply { ref = "I1" }); addWife(SpouseRef().apply { ref = "I2" }); addChild(ChildRef().apply { ref = "I3" })
        })
        addFamily(Family().apply {
            id = "F2"
            addHusband(SpouseRef().apply { ref = "I5" }); addChild(ChildRef().apply { ref = "I1" }); addChild(ChildRef().apply { ref = "I8" })
        })
    }.also { host.trees[1] = it }

    private fun job(kind: JobKind, key: String, geniId: String? = null, person: String? = null, family: String? = null, anchors: List<String> = emptyList()) =
        UploadJob(id = 3, treeId = 1, key = key, kind = kind, geniId = geniId, personGedcomId = person, familyGedcomId = family, anchors = anchors,
            params = listOf("first_name" to "New", "is_alive" to "true"), label = "New", localHash = 1)

    private val updateProfile = UploadJob(id = 1, treeId = 1, key = "profile:profile-36430", kind = JobKind.UPDATE_PROFILE,
        geniId = "profile-36430", params = listOf("occupation" to "Driver"), label = "Test User", localHash = 1)

    @Test
    fun updateProfilePostsParamsAndSavesSnapshot() = runTest {
        tree()
        store.saveLinks(listOf(link("profile-36430", "I1")))
        server.enqueue(ok("""{"id":"profile-36430","occupation":"Driver"}"""))
        val full = """{"id":"profile-36430","occupation":"Driver","about_me":"Hi","updated_at":"1795000000"}"""
        server.enqueue(ok(full))
        assertEquals(Outcome.Done, uploader().run(updateProfile))
        val post = server.takeRequest()
        assertEquals("POST", post.method)
        assertEquals("/api/profile-36430/update", post.url.encodedPath)
        assertEquals("occupation=Driver", post.body!!.utf8())
        assertEquals("profile-36430", server.takeRequest().url.queryParameter("ids"))
        val saved = store.link(1, "profile-36430")!!
        assertEquals(full, saved.snapshot)
        assertEquals("1795000000", saved.geniUpdatedAt)
        assertNull(saved.verifiedHash)
        assertEquals("I1", saved.gedcomId)
        assertTrue(saved.fullyLoaded)
    }

    @Test
    fun updateUnionPostsToUnion() = runTest {
        tree()
        store.saveLinks(listOf(link("union-10106", "F1")))
        server.enqueue(ok("""{"id":"union-10106"}"""))
        server.enqueue(ok("""{"id":"union-10106","marriage":{"date":{"year":2012}}}"""))
        val job = updateProfile.copy(key = "union:union-10106", kind = JobKind.UPDATE_UNION, geniId = "union-10106", params = listOf("marriage[date][year]" to "2012"))
        assertEquals(Outcome.Done, uploader().run(job))
        assertEquals("/api/union-10106/update", server.takeRequest().url.encodedPath)
        assertTrue(store.link(1, "union-10106")!!.snapshot!!.contains("2012"))
    }

    @Test
    fun addChildToLinkedUnion() = runTest {
        val gedcom = tree()
        store.saveLinks(listOf(link("profile-36430", "I1"), link("union-10106", "F1")))
        server.enqueue(ok("""{"id":"profile-50","unions":["https://api.sandbox.geni.com/union-10106"]}"""))
        server.enqueue(ok("""{"id":"profile-50","first_name":"New","updated_at":"1795000001"}"""))
        server.enqueue(ok("""{"id":"union-10106","children":["profile-50"]}"""))
        val outcome = uploader().run(job(JobKind.ADD_CHILD, "add:I3", person = "I3", family = "F1", anchors = listOf("partner:I1", "partner:I2")))
        assertEquals(Outcome.Done, outcome)
        val post = server.takeRequest()
        assertEquals("/api/union-10106/add-child", post.url.encodedPath)
        assertEquals("first_name=New&is_alive=true", post.body!!.utf8())
        assertEquals("profile-50", GeniMapper.getGeniId(gedcom.people.single { it.id == "I3" }))
        val child = store.link(1, "profile-50")!!
        assertEquals("I3", child.gedcomId)
        assertFalse(child.fullyLoaded)
        assertEquals(0L, child.loadedAtMillis)
        assertTrue(child.snapshot!!.contains("1795000001"))
        assertTrue(store.link(1, "union-10106")!!.snapshot!!.contains("profile-50"))
    }

    @Test
    fun addParentCreatesUnionLink() = runTest {
        val gedcom = tree()
        store.saveLinks(listOf(link("profile-36430", "I1")))
        server.enqueue(ok("""{"id":"profile-51","unions":["https://api.sandbox.geni.com/union-77"]}"""))
        server.enqueue(ok("""{"id":"profile-51"}"""))
        server.enqueue(ok("""{"id":"union-77","partners":["profile-51"],"children":["profile-36430"]}"""))
        assertEquals(Outcome.Done, uploader().run(job(JobKind.ADD_PARTNER, "add:I5", person = "I5", family = "F2", anchors = listOf("child:I1", "child:I8"))))
        assertEquals("/api/profile-36430/add-parent", server.takeRequest().url.encodedPath)
        assertEquals("union-77", GeniMapper.getGeniId(gedcom.families.single { it.id == "F2" }))
        assertEquals("profile-51", GeniMapper.getGeniId(gedcom.people.single { it.id == "I5" }))
        assertEquals("F2", store.link(1, "union-77")!!.gedcomId)
        assertTrue(store.link(1, "union-77")!!.snapshot!!.contains("profile-51"))
    }

    @Test
    fun addPartnerToLinkedPartner() = runTest {
        tree()
        store.saveLinks(listOf(link("profile-36430", "I1")))
        server.enqueue(ok("""{"id":"profile-52","unions":["https://api.sandbox.geni.com/union-78"]}"""))
        server.enqueue(ok("""{"id":"profile-52"}"""))
        server.enqueue(ok("""{"id":"union-78"}"""))
        assertEquals(Outcome.Done, uploader().run(job(JobKind.ADD_PARTNER, "add:I5", person = "I5", family = "F2", anchors = listOf("partner:I1"))))
        assertEquals("/api/profile-36430/add-partner", server.takeRequest().url.encodedPath)
    }

    @Test
    fun childOfNewPersonUsesTheNewUnion() = runTest {
        tree()
        store.saveLinks(listOf(link("profile-36430", "I1"), link("profile-51", "I5"), link("union-77", "F2")))
        server.enqueue(ok("""{"id":"profile-53","unions":["https://api.sandbox.geni.com/union-77"]}"""))
        server.enqueue(ok("""{"id":"profile-53"}"""))
        server.enqueue(ok("""{"id":"union-77"}"""))
        assertEquals(Outcome.Done, uploader().run(job(JobKind.ADD_CHILD, "add:I8", person = "I8", family = "F2", anchors = listOf("partner:I5"))))
        assertEquals("/api/union-77/add-child", server.takeRequest().url.encodedPath)
    }

    @Test
    fun alreadyLinkedPersonIsNotAddedTwice() = runTest {
        tree()
        store.saveLinks(listOf(link("profile-50", "I3"), link("union-10106", "F1")))
        assertEquals(Outcome.Done, uploader().run(job(JobKind.ADD_CHILD, "add:I3", person = "I3", family = "F1", anchors = listOf("partner:I1"))))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun missingAnchorFails() = runTest {
        tree()
        val outcome = uploader().run(job(JobKind.ADD_CHILD, "add:I8", person = "I8", family = "F2", anchors = listOf("partner:I5")))
        assertTrue(outcome is Outcome.Failed)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun http403Fails() = runTest {
        tree()
        store.saveLinks(listOf(link("profile-36430", "I1")))
        server.enqueue(error(403, """{"error":{"type":"ApiException","message":"Access Denied"}}"""))
        assertEquals(Outcome.Failed("Access Denied"), uploader().run(updateProfile))
        assertEquals("{}", store.link(1, "profile-36430")!!.snapshot)
    }

    @Test
    fun http503Retries() = runTest {
        tree()
        server.enqueue(error(503))
        assertTrue(uploader().run(updateProfile) is Outcome.Retry)
    }

    @Test
    fun http401NeedsLogin() = runTest {
        tree()
        server.enqueue(error(401))
        assertEquals(Outcome.NeedsLogin, uploader().run(updateProfile))
    }

    @Test
    fun connectionLostOnUpdateRetries() = runTest {
        tree()
        server.close()
        assertTrue(uploader().run(updateProfile) is Outcome.Retry)
    }

    @Test
    fun connectionLostOnAddFails() = runTest {
        tree()
        store.saveLinks(listOf(link("union-10106", "F1")))
        // Geni got the request; the answer never came
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        val outcome = uploader().run(job(JobKind.ADD_CHILD, "add:I3", person = "I3", family = "F1", anchors = listOf("partner:I1")))
        assertTrue(outcome is Outcome.Failed)
        assertNull(store.linkByGedcomId(1, "I3"))
    }

    @Test
    fun addWhileOfflineRetries() = runTest {
        tree()
        store.saveLinks(listOf(link("union-10106", "F1")))
        server.close() // Connection refused: nothing reached Geni
        val outcome = uploader().run(job(JobKind.ADD_CHILD, "add:I3", person = "I3", family = "F1", anchors = listOf("partner:I1")))
        assertTrue(outcome is Outcome.Retry)
        assertNull(store.linkByGedcomId(1, "I3"))
    }

    @Test
    fun addWithUnknownHostRetries() = runTest {
        tree()
        store.saveLinks(listOf(link("union-10106", "F1")))
        val offline = Uploader(GeniClient("http://geni.invalid/api/".toHttpUrl(), tokens, RateLimiter { testScheduler.currentTime }), store, host)
        assertTrue(offline.run(job(JobKind.ADD_CHILD, "add:I3", person = "I3", family = "F1", anchors = listOf("partner:I1"))) is Outcome.Retry)
    }

    @Test
    fun snapshotFetchFailureAfterAddStillLinks() = runTest {
        val gedcom = tree()
        store.saveLinks(listOf(link("union-10106", "F1")))
        server.enqueue(ok("""{"id":"profile-50","unions":["https://api.sandbox.geni.com/union-10106"]}"""))
        server.enqueue(error(503))
        assertEquals(Outcome.Done, uploader().run(job(JobKind.ADD_CHILD, "add:I3", person = "I3", family = "F1", anchors = listOf("partner:I1"))))
        assertEquals("profile-50", GeniMapper.getGeniId(gedcom.people.single { it.id == "I3" }))
        assertEquals("I3", store.link(1, "profile-50")!!.gedcomId)
    }

    private val addI3 get() = job(JobKind.ADD_CHILD, "add:I3", person = "I3", family = "F1", anchors = listOf("partner:I1", "partner:I2"))

    @Test
    fun add503FailsWithoutRetry() = runTest {
        tree()
        store.saveLinks(listOf(link("union-10106", "F1")))
        server.enqueue(error(503))
        val outcome = uploader().run(addI3)
        assertTrue(outcome is Outcome.Failed)
        assertTrue((outcome as Outcome.Failed).message.contains("check on Geni"))
    }

    @Test
    fun addSavesLinkBeforeTag() = runTest {
        tree()
        store.saveLinks(listOf(link("union-10106", "F1")))
        var linkedAtEdit: GeniLink? = null
        editHook = { linkedAtEdit = runBlockingLink() }
        server.enqueue(ok("""{"id":"profile-50","unions":["union-10106"]}"""))
        server.enqueue(ok("""{"id":"profile-50"}"""))
        server.enqueue(ok("""{"id":"union-10106"}"""))
        assertEquals(Outcome.Done, uploader().run(addI3))
        assertEquals("profile-50", linkedAtEdit?.geniId)
    }

    private fun runBlockingLink() = kotlinx.coroutines.runBlocking { store.linkByGedcomId(1, "I3") }

    @Test
    fun treeEditFailureAfterAddIsStillDone() = runTest {
        tree()
        store.saveLinks(listOf(link("union-10106", "F1")))
        editHook = { error("Tree 1 not readable") }
        server.enqueue(ok("""{"id":"profile-50","unions":["union-10106"]}"""))
        server.enqueue(ok("""{"id":"profile-50"}"""))
        server.enqueue(ok("""{"id":"union-10106"}"""))
        assertEquals(Outcome.Done, uploader().run(addI3))
        assertEquals("I3", store.link(1, "profile-50")!!.gedcomId)
    }

    @Test
    fun nonJsonAddResponseFails() = runTest {
        tree()
        store.saveLinks(listOf(link("union-10106", "F1")))
        server.enqueue(ok("<html>oops</html>"))
        val outcome = uploader().run(addI3)
        assertTrue((outcome as Outcome.Failed).message.contains("check on Geni"))
    }

    @Test
    fun updateSnapshotFetchFailureRetries() = runTest {
        tree()
        store.saveLinks(listOf(link("profile-36430", "I1")))
        server.enqueue(ok("""{"id":"profile-36430"}"""))
        server.enqueue(error(503))
        assertTrue(uploader().run(updateProfile) is Outcome.Retry)
        assertEquals("{}", store.link(1, "profile-36430")!!.snapshot)
    }

    @Test
    fun childOfTwoParentsWithoutUnionFails() = runTest {
        tree()
        // F2 gets a second partner I2: both parents on Geni, but no union to add the child to
        host.trees.getValue(1).families.single { it.id == "F2" }.addWife(SpouseRef().apply { ref = "I2" })
        store.saveLinks(listOf(link("profile-51", "I5"), link("profile-36433", "I2")))
        val outcome = uploader().run(job(JobKind.ADD_CHILD, "add:I8", person = "I8", family = "F2", anchors = listOf("partner:I5", "partner:I2")))
        assertTrue(outcome is Outcome.Failed)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun linkSaveFailureAfterAddFails() = runTest {
        tree()
        store.saveLinks(listOf(link("union-10106", "F1")))
        failLinkSave = true
        server.enqueue(ok("""{"id":"profile-50","unions":["union-10106"]}"""))
        val outcome = uploader().run(addI3)
        assertTrue((outcome as Outcome.Failed).message.contains("profile-50"))
    }

    @Test
    fun snapshotRefreshKeepsOtherLinkFields() = runTest {
        tree()
        store.saveLinks(listOf(link("profile-36430", "I1")))
        server.enqueue(ok("""{"id":"profile-36430"}"""))
        server.enqueue(ok("""{"id":"profile-36430","occupation":"Driver","updated_at":"9"}"""))
        assertEquals(Outcome.Done, uploader().run(updateProfile))
        val saved = store.link(1, "profile-36430")!!
        assertEquals(5L, saved.loadedAtMillis)
        assertEquals("9", saved.geniUpdatedAt)
    }

    @Test
    fun addStoresSentHashOnLink() = runTest {
        tree()
        store.saveLinks(listOf(link("union-10106", "F1")))
        server.enqueue(ok("""{"id":"profile-50","unions":["union-10106"]}"""))
        server.enqueue(error(503))
        assertEquals(Outcome.Done, uploader().run(job(JobKind.ADD_CHILD, "add:I3", person = "I3", family = "F1", anchors = listOf("partner:I1"))))
        val saved = store.link(1, "profile-50")!!
        assertNull(saved.snapshot)
        assertEquals(1, saved.verifiedHash) // The job's localHash: what Geni has now, until a snapshot replaces it
    }

    @Test
    fun snapshotRefreshNeverOverwritesANewerSnapshot() = runTest {
        tree()
        store.saveLinks(listOf(link("profile-36430", "I1")))
        server.enqueue(ok("""{"id":"profile-36430"}"""))
        server.enqueue(ok("""{"id":"profile-36430","occupation":"Old"}"""))
        server.enqueue(ok("""{"id":"profile-36430","occupation":"Fresh"}"""))
        var loads = 0
        beforeSnapshotWrite = {
            // A load stores a newer snapshot after the uploader fetched its own
            if (loads++ == 0) store.saveLinks(listOf(store.link(1, "profile-36430")!!.copy(snapshot = """{"id":"profile-36430","occupation":"Loaded"}""")))
        }
        assertEquals(Outcome.Done, uploader().run(updateProfile))
        assertEquals("""{"id":"profile-36430","occupation":"Fresh"}""", store.link(1, "profile-36430")!!.snapshot)
    }

    @Test
    fun snapshotConflictEveryTimeRetries() = runTest {
        tree()
        store.saveLinks(listOf(link("profile-36430", "I1")))
        server.enqueue(ok("""{"id":"profile-36430"}"""))
        repeat(3) { server.enqueue(ok("""{"id":"profile-36430","occupation":"Driver"}""")) }
        var loads = 0
        beforeSnapshotWrite = { store.saveLinks(listOf(store.link(1, "profile-36430")!!.copy(snapshot = """{"loaded":${loads++}}"""))) }
        assertTrue(uploader().run(updateProfile) is Outcome.Retry)
    }

    @Test
    fun updateWithoutParamsOnlyFetchesSnapshot() = runTest {
        tree()
        store.saveLinks(listOf(link("profile-36430", "I1", snapshot = null)))
        server.enqueue(ok("""{"id":"profile-36430","first_name":"Olga"}"""))
        assertEquals(Outcome.Done, uploader().run(updateProfile.copy(params = emptyList())))
        assertEquals("GET", server.takeRequest().method)
        assertEquals(1, server.requestCount)
        assertEquals("""{"id":"profile-36430","first_name":"Olga"}""", store.link(1, "profile-36430")!!.snapshot)
    }
}
