package nl.tudelft.trustchain.frostdao.ipv8

import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import nl.tudelft.ipv8.Peer
import nl.tudelft.ipv8.keyvault.JavaCryptoProvider
import nl.tudelft.trustchain.frostdao.database.FrostDatabase
import nl.tudelft.trustchain.frostdao.database.MeDao
import nl.tudelft.trustchain.frostdao.database.ReceivedMessageDao
import nl.tudelft.trustchain.frostdao.ipv8.message.FrostMessage
import nl.tudelft.trustchain.frostdao.ipv8.message.KeyGenCommitments
import nl.tudelft.trustchain.frostdao.ipv8.message.Preprocess
import nl.tudelft.trustchain.frostdao.ipv8.message.SignRequestResponse
import org.junit.Assert
import org.junit.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

class FrostManagerTest {

    init {
        System.load("C:\\Users\\Rahim\\Desktop\\superapp_frost\\frostdao\\src\\test\\libs\\rust_code.dll")
    }

    fun createBasicManager(config: FrostManagerConfig = FrostManager.defaultConfig): FrostManager {
        val myPeer = Peer(JavaCryptoProvider.generateKey())
        val other = Peer(JavaCryptoProvider.generateKey().pub())
        val receiveChannel = Channel<Pair<Peer, FrostMessage>>()

        val db = mockk<FrostDatabase>(relaxed = true)
        val medao = mockk<MeDao>(relaxed = true)

        every { db.meDao() } returns medao
        return FrostManager(config = config,
            receiveChannel = receiveChannel.receiveAsFlow(),
            db = db,
            networkManager = object : NetworkManager() {
                override suspend fun send(peer: Peer, msg: FrostMessage): Pair<Boolean, Int> {
                    return true to 0
                }

                override fun getMyPeer() = myPeer

                override fun getPeerFromMid(mid: String): Peer {
                    TODO("Not yet implemented")
                }

                override fun peers(): List<Peer> = listOf(other)

                override suspend fun broadcast(msg: FrostMessage, recipients: List<Peer>): Pair<Boolean, Int> =
                    true to 0

            }

        )

    }

    @Test
    fun `can only join if in the right state`() = runBlocking {
        val manager = createBasicManager()

        manager.state = FrostState.ReadyForSign
        manager.joinGroup(manager.networkManager.peers().first())

        Assert.assertFalse(manager.state is FrostState.ProposedJoin)
    }

    @Test
    fun `A request to join message should be sent when joining`() = runBlocking {
        val myPeer = Peer(JavaCryptoProvider.generateKey())
        val other = Peer(JavaCryptoProvider.generateKey().pub())
        val receiveChannel = Channel<Pair<Peer, FrostMessage>>()

        val sendSignal = Mutex(true)
        val db = mockk<FrostDatabase>(relaxed = true)
        val medao = mockk<MeDao>(relaxed = true)

        every { db.meDao() } returns medao
        val manager = FrostManager(
            receiveChannel = receiveChannel.receiveAsFlow(),
            db = db,
            networkManager = object : NetworkManager() {
                override suspend fun send(peer: Peer, msg: FrostMessage): Pair<Boolean, Int> {
                    sendSignal.unlock()
                    return true to 0
                }

                override fun getMyPeer() = myPeer

                override fun getPeerFromMid(mid: String): Peer {
                    TODO("Not yet implemented")
                }

                override fun peers(): List<Peer> = listOf(other)

                override suspend fun broadcast(msg: FrostMessage, recipients: List<Peer>): Pair<Boolean, Int> =
                    true to 0

            }

        )

        val job = launch {
            manager.joinGroup(other)
        }

        // there is a delay of 10 ms when sending a msg
        val signal = withTimeoutOrNull(100) {
            sendSignal.lock()
        }

        Assert.assertEquals(Unit, signal)
        Assert.assertTrue(manager.state is FrostState.ProposedJoin)
        job.cancel()
        return@runBlocking
    }

    @Test
    fun `Joining should timeout if no response was received`() = runBlocking {
        val manager = createBasicManager(FrostManager.defaultConfig.copy(waitForKeygenResponseTimeout = 10.milliseconds))

        val timeoutSignal = Mutex(true)

        launch {
            manager.updatesChannel.collect {
                if (it is Update.TimeOut) {
                    timeoutSignal.unlock()
                    cancel()
                }
            }
        }

        manager.joinGroup(manager.networkManager.peers().first())

        timeoutSignal.lock()

        val signal = withTimeoutOrNull(100) {
            timeoutSignal.lock()
        }

        Assert.assertNull(signal)


    }

    @Test
    fun `Keygen should work with two participants`() = runBlocking {
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0

        val peer1 = Peer(JavaCryptoProvider.generateKey())
        val peer2 = Peer(JavaCryptoProvider.generateKey())
        val receiveChannel1 = Channel<Pair<Peer, FrostMessage>>()
        val receiveChannel2 = Channel<Pair<Peer, FrostMessage>>()

        val manager1 = run {
            val db = mockk<FrostDatabase>(relaxed = true)
            val medao = mockk<MeDao>(relaxed = true)
            val messageDao = mockk<ReceivedMessageDao>(relaxed = true)
            every { db.receivedMessageDao() } returns messageDao
            every { db.meDao() } returns medao
            FrostManager(
                receiveChannel = receiveChannel1.receiveAsFlow(),
                db = db,
                networkManager = object : NetworkManager() {
                    override suspend fun send(peer: Peer, msg: FrostMessage): Pair<Boolean, Int> {
                        receiveChannel2.send(peer1 to msg)
                        return true to 0
                    }

                    override fun getMyPeer() = peer1

                    override fun getPeerFromMid(mid: String) = peer2

                    override fun peers(): List<Peer> = listOf(peer2)

                    override suspend fun broadcast(msg: FrostMessage, recipients: List<Peer>): Pair<Boolean, Int> {
                        receiveChannel2.send(peer1 to msg)
                        return true to 0
                    }

                }

            )
        }
        val manager2 = run {
            val db = mockk<FrostDatabase>(relaxed = true)
            val medao = mockk<MeDao>(relaxed = true)
            val messageDao = mockk<ReceivedMessageDao>(relaxed = true)
            every { db.receivedMessageDao() } returns messageDao
            every { db.meDao() } returns medao
            FrostManager(
                receiveChannel = receiveChannel2.receiveAsFlow(),
                db = db,
                networkManager = object : NetworkManager() {
                    override suspend fun send(peer: Peer, msg: FrostMessage): Pair<Boolean, Int> {
                        receiveChannel1.send(peer2 to msg)
                        return true to 0
                    }

                    override fun getMyPeer() = peer2

                    override fun getPeerFromMid(mid: String) = peer1

                    override fun peers(): List<Peer> = listOf(peer1)

                    override suspend fun broadcast(msg: FrostMessage, recipients: List<Peer>): Pair<Boolean, Int> {
                        receiveChannel1.send(peer2 to msg)
                        return true to 0
                    }

                }

            )
        }

        val signal1 = Mutex(true)
        val signal2 = Mutex(true)
        launch {
            manager1.updatesChannel.collect {
                if (it is Update.KeyGenDone) {
                    signal1.unlock()
                    cancel()
                }
            }
        }

        launch {
            manager2.updatesChannel.collect {
                if (it is Update.KeyGenDone) {
                    signal2.unlock()
                    cancel()
                }
            }
        }

        manager1.joinGroup(peer2)

        signal1.lock()
        signal2.lock()

        // we receive the update before the frostInfo is created
        // todo maybe change that? It isn't really a problem in real life
        delay(10)

        Assert.assertNotNull(manager1.frostInfo)
        Assert.assertNotNull(manager2.frostInfo)
    }

    @Test
    fun `Keygen should timeout when the first FROST message is not received`() = runBlocking {
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0

        val peer1 = Peer(JavaCryptoProvider.generateKey())
        val peer2 = Peer(JavaCryptoProvider.generateKey())
        val receiveChannel1 = Channel<Pair<Peer, FrostMessage>>()
        val receiveChannel2 = Channel<Pair<Peer, FrostMessage>>()

        val manager1 = run {
            val db = mockk<FrostDatabase>(relaxed = true)
            val medao = mockk<MeDao>(relaxed = true)
            val messageDao = mockk<ReceivedMessageDao>(relaxed = true)
            every { db.receivedMessageDao() } returns messageDao
            every { db.meDao() } returns medao
            FrostManager(
                receiveChannel = receiveChannel1.receiveAsFlow(),
                db = db,
                networkManager = object : NetworkManager() {
                    override suspend fun send(peer: Peer, msg: FrostMessage): Pair<Boolean, Int> {
                        receiveChannel2.send(peer1 to msg)
                        return true to 0
                    }

                    override fun getMyPeer() = peer1

                    override fun getPeerFromMid(mid: String) = peer2

                    override fun peers(): List<Peer> = listOf(peer2)

                    override suspend fun broadcast(msg: FrostMessage, recipients: List<Peer>): Pair<Boolean, Int> {
                        if (msg is KeyGenCommitments) return false to 0
                        receiveChannel2.send(peer1 to msg)
                        return true to 0
                    }

                }

            )
        }
        val manager2 = run {
            val db = mockk<FrostDatabase>(relaxed = true)
            val medao = mockk<MeDao>(relaxed = true)
            val messageDao = mockk<ReceivedMessageDao>(relaxed = true)
            every { db.receivedMessageDao() } returns messageDao
            every { db.meDao() } returns medao
            FrostManager(receiveChannel = receiveChannel2.receiveAsFlow(),
                config = FrostManager.defaultConfig.copy(waitForKeygenResponseTimeout = 20.milliseconds),
                db = db,
                networkManager = object : NetworkManager() {
                    override suspend fun send(peer: Peer, msg: FrostMessage): Pair<Boolean, Int> {
                        receiveChannel1.send(peer2 to msg)
                        return true to 0
                    }

                    override fun getMyPeer() = peer2

                    override fun getPeerFromMid(mid: String) = peer1

                    override fun peers(): List<Peer> = listOf(peer1)

                    override suspend fun broadcast(msg: FrostMessage, recipients: List<Peer>): Pair<Boolean, Int> {
                        receiveChannel1.send(peer2 to msg)
                        return true to 0
                    }

                }

            )
        }

        val signal1 = Mutex(true)
        val signal2 = Mutex(true)
        launch {
            manager1.updatesChannel.collect {
                if (it is Update.TimeOut) {
                    signal1.unlock()
                    cancel()
                }
            }
        }

        launch {
            manager2.updatesChannel.collect {
                if (it is Update.TimeOut) {
                    signal2.unlock()
                    cancel()
                }
            }
        }

        manager1.joinGroup(peer2)

        signal1.lock()
        signal2.lock()

        Assert.assertNull(manager1.frostInfo)
        Assert.assertNull(manager2.frostInfo)
    }

    @Test
    fun `Signing should work with two participants`() = runBlocking {
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0

        val peer1 = Peer(JavaCryptoProvider.generateKey())
        val peer2 = Peer(JavaCryptoProvider.generateKey())
        val receiveChannel1 = Channel<Pair<Peer, FrostMessage>>()
        val receiveChannel2 = Channel<Pair<Peer, FrostMessage>>()

        val manager1 = run {
            val db = mockk<FrostDatabase>(relaxed = true)
            val medao = mockk<MeDao>(relaxed = true)
            val messageDao = mockk<ReceivedMessageDao>(relaxed = true)
            every { db.receivedMessageDao() } returns messageDao
            every { db.meDao() } returns medao
            FrostManager(
                receiveChannel = receiveChannel1.receiveAsFlow(),
                db = db,
                networkManager = object : NetworkManager() {
                    override suspend fun send(peer: Peer, msg: FrostMessage): Pair<Boolean, Int> {
                        receiveChannel2.send(peer1 to msg)
                        return true to 0
                    }

                    override fun getMyPeer() = peer1

                    override fun getPeerFromMid(mid: String) = peer2

                    override fun peers(): List<Peer> = listOf(peer2)

                    override suspend fun broadcast(msg: FrostMessage, recipients: List<Peer>): Pair<Boolean, Int> {
                        receiveChannel2.send(peer1 to msg)
                        return true to 0
                    }

                }

            )
        }
        val manager2 = run {
            val db = mockk<FrostDatabase>(relaxed = true)
            val medao = mockk<MeDao>(relaxed = true)
            val messageDao = mockk<ReceivedMessageDao>(relaxed = true)
            every { db.receivedMessageDao() } returns messageDao
            every { db.meDao() } returns medao
            FrostManager(
                receiveChannel = receiveChannel2.receiveAsFlow(),
                db = db,
                networkManager = object : NetworkManager() {
                    override suspend fun send(peer: Peer, msg: FrostMessage): Pair<Boolean, Int> {
                        receiveChannel1.send(peer2 to msg)
                        return true to 0
                    }

                    override fun getMyPeer() = peer2

                    override fun getPeerFromMid(mid: String) = peer1

                    override fun peers(): List<Peer> = listOf(peer1)

                    override suspend fun broadcast(msg: FrostMessage, recipients: List<Peer>): Pair<Boolean, Int> {
                        receiveChannel1.send(peer2 to msg)
                        return true to 0
                    }

                }

            )
        }

        val signal1 = Mutex(true)
        val signal2 = Mutex(true)
        launch {
            manager1.updatesChannel.collect {
                if (it is Update.KeyGenDone) {
                    signal1.unlock()
                    cancel()
                }
            }
        }

        launch {
            manager2.updatesChannel.collect {
                if (it is Update.KeyGenDone) {
                    signal2.unlock()
                    cancel()
                }
            }
        }

        manager1.joinGroup(peer2)

        signal1.lock()
        signal2.lock()

        // we receive the update before the frostInfo is created
        // todo maybe change that? Though It isn't really a problem in real life
        delay(10)

        launch {
            manager2.updatesChannel.collect {
                if (it is Update.SignRequestReceived) {
                    signal2.unlock()
                    cancel()
                }
            }
        }

        val signData = Random.nextBytes(32)

        val (_, id) = manager1.proposeSignAsync(signData)
        signal2.lock()

        launch {
            manager1.updatesChannel.collect {
                if (it is Update.SignDone) {
                    signal1.unlock()
                    cancel()
                }
            }
        }

        launch {
            manager2.updatesChannel.collect {
                if (it is Update.SignDone) {
                    signal2.unlock()
                    cancel()
                }
            }
        }

        manager2.acceptProposedSign(id, peer1.mid, signData)

        val done = withTimeoutOrNull(100) {
            signal1.lock()
            signal2.lock()
        }

        Assert.assertNotNull(done)
    }

    @Test
    fun `Signing proposal should timeout if no response was received`() = runBlocking {
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0

        val peer1 = Peer(JavaCryptoProvider.generateKey())
        val peer2 = Peer(JavaCryptoProvider.generateKey())
        val receiveChannel1 = Channel<Pair<Peer, FrostMessage>>()
        val receiveChannel2 = Channel<Pair<Peer, FrostMessage>>()

        val manager1 = run {
            val db = mockk<FrostDatabase>(relaxed = true)
            val medao = mockk<MeDao>(relaxed = true)
            val messageDao = mockk<ReceivedMessageDao>(relaxed = true)
            every { db.receivedMessageDao() } returns messageDao
            every { db.meDao() } returns medao
            FrostManager(
                receiveChannel = receiveChannel1.receiveAsFlow(),
                config = FrostManager.defaultConfig.copy(waitForSignResponseTimeout = 10.milliseconds),
                db = db,
                networkManager = object : NetworkManager() {
                    override suspend fun send(peer: Peer, msg: FrostMessage): Pair<Boolean, Int> {
                        receiveChannel2.send(peer1 to msg)
                        return true to 0
                    }

                    override fun getMyPeer() = peer1

                    override fun getPeerFromMid(mid: String) = peer2

                    override fun peers(): List<Peer> = listOf(peer2)

                    override suspend fun broadcast(msg: FrostMessage, recipients: List<Peer>): Pair<Boolean, Int> {
                        receiveChannel2.send(peer1 to msg)
                        return true to 0
                    }

                }

            )
        }
        val manager2 = run {
            val db = mockk<FrostDatabase>(relaxed = true)
            val medao = mockk<MeDao>(relaxed = true)
            val messageDao = mockk<ReceivedMessageDao>(relaxed = true)
            every { db.receivedMessageDao() } returns messageDao
            every { db.meDao() } returns medao
            FrostManager(
                receiveChannel = receiveChannel2.receiveAsFlow(),
                db = db,
                networkManager = object : NetworkManager() {
                    override suspend fun send(peer: Peer, msg: FrostMessage): Pair<Boolean, Int> {
                        if (msg is SignRequestResponse) return false to 0
                        receiveChannel1.send(peer2 to msg)
                        return true to 0
                    }

                    override fun getMyPeer() = peer2

                    override fun getPeerFromMid(mid: String) = peer1

                    override fun peers(): List<Peer> = listOf(peer1)

                    override suspend fun broadcast(msg: FrostMessage, recipients: List<Peer>): Pair<Boolean, Int> {
                        receiveChannel1.send(peer2 to msg)
                        return true to 0
                    }

                }

            )
        }

        val signal1 = Mutex(true)
        val signal2 = Mutex(true)
        launch {
            manager1.updatesChannel.collect {
                if (it is Update.KeyGenDone) {
                    signal1.unlock()
                    cancel()
                }
            }
        }

        launch {
            manager2.updatesChannel.collect {
                if (it is Update.KeyGenDone) {
                    signal2.unlock()
                    cancel()
                }
            }
        }

        manager1.joinGroup(peer2)

        signal1.lock()
        signal2.lock()

        // we receive the update before the frostInfo is created
        // todo maybe change that? Though It isn't really a problem in real life
        delay(10)


        val signData = Random.nextBytes(32)

        launch {
            manager1.updatesChannel.collect {
                if (it is Update.TimeOut) {
                    signal1.unlock()
                    cancel()
                }
            }
        }

        val (_, _) = manager1.proposeSignAsync(signData)

        /* todo should I be doing this? If I don't, then the tests may
             hang, but if I do then the test may fail even if the code is not wrong. */
        val done = withTimeoutOrNull(100) {
            signal1.lock()
        }

        Assert.assertNotNull(done)

    }

    @Test
    fun `Signing should timeout when the first FROST message is not received`() = runBlocking {
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0

        val peer1 = Peer(JavaCryptoProvider.generateKey())
        val peer2 = Peer(JavaCryptoProvider.generateKey())
        val receiveChannel1 = Channel<Pair<Peer, FrostMessage>>()
        val receiveChannel2 = Channel<Pair<Peer, FrostMessage>>()

        val manager1 = run {
            val db = mockk<FrostDatabase>(relaxed = true)
            val medao = mockk<MeDao>(relaxed = true)
            val messageDao = mockk<ReceivedMessageDao>(relaxed = true)
            every { db.receivedMessageDao() } returns messageDao
            every { db.meDao() } returns medao
            FrostManager(
                receiveChannel = receiveChannel1.receiveAsFlow(),
                config = FrostManager.defaultConfig.copy(signTimeout = 20.milliseconds),
                db = db,
                networkManager = object : NetworkManager() {
                    override suspend fun send(peer: Peer, msg: FrostMessage): Pair<Boolean, Int> {
                        receiveChannel2.send(peer1 to msg)
                        return true to 0
                    }

                    override fun getMyPeer() = peer1

                    override fun getPeerFromMid(mid: String) = peer2

                    override fun peers(): List<Peer> = listOf(peer2)

                    override suspend fun broadcast(msg: FrostMessage, recipients: List<Peer>): Pair<Boolean, Int> {
                        receiveChannel2.send(peer1 to msg)
                        return true to 0
                    }

                }

            )
        }
        val manager2 = run {
            val db = mockk<FrostDatabase>(relaxed = true)
            val medao = mockk<MeDao>(relaxed = true)
            val messageDao = mockk<ReceivedMessageDao>(relaxed = true)
            every { db.receivedMessageDao() } returns messageDao
            every { db.meDao() } returns medao
            FrostManager(
                receiveChannel = receiveChannel2.receiveAsFlow(),
                db = db,
                networkManager = object : NetworkManager() {
                    override suspend fun send(peer: Peer, msg: FrostMessage): Pair<Boolean, Int> {
                        receiveChannel1.send(peer2 to msg)
                        return true to 0
                    }

                    override fun getMyPeer() = peer2

                    override fun getPeerFromMid(mid: String) = peer1

                    override fun peers(): List<Peer> = listOf(peer1)

                    override suspend fun broadcast(msg: FrostMessage, recipients: List<Peer>): Pair<Boolean, Int> {
                        if (msg is Preprocess){
                            return false to 0
                        }
                        receiveChannel1.send(peer2 to msg)
                        return true to 0
                    }

                }

            )
        }

        val signal1 = Mutex(true)
        val signal2 = Mutex(true)
        launch {
            manager1.updatesChannel.collect {
                if (it is Update.KeyGenDone) {
                    signal1.unlock()
                    cancel()
                }
            }
        }

        launch {
            manager2.updatesChannel.collect {
                if (it is Update.KeyGenDone) {
                    signal2.unlock()
                    cancel()
                }
            }
        }

        manager1.joinGroup(peer2)

        signal1.lock()
        signal2.lock()

        // we receive the update before the frostInfo is created
        // todo maybe change that? Though It isn't really a problem in real life
        delay(10)

        launch {
            manager2.updatesChannel.collect {
                if (it is Update.SignRequestReceived) {
                    signal2.unlock()
                    cancel()
                }
            }
        }

        val signData = Random.nextBytes(32)

        val (_, id) = manager1.proposeSignAsync(signData)
        signal2.lock()

        launch {
            manager1.updatesChannel.collect{
                if (it is Update.TimeOut){
                    signal1.unlock()
                    cancel()
                }
            }
        }

        manager2.acceptProposedSign(id, peer1.mid, signData)

        val done = withTimeoutOrNull(100) {
            signal1.lock()
        }

        Assert.assertNotNull(done)
    }

}
