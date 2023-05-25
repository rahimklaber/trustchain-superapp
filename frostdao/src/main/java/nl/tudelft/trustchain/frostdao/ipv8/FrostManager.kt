package nl.tudelft.trustchain.frostdao.ipv8

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import nl.tudelft.ipv8.Peer
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.trustchain.frostdao.SchnorrAgent
import nl.tudelft.trustchain.frostdao.SchnorrAgentMessage
import nl.tudelft.trustchain.frostdao.SchnorrAgentOutput
import nl.tudelft.trustchain.frostdao.bitcoin.BitcoinService
import nl.tudelft.trustchain.frostdao.database.FrostDatabase
import nl.tudelft.trustchain.frostdao.database.Me
import nl.tudelft.trustchain.frostdao.database.Request
import nl.tudelft.trustchain.frostdao.ipv8.message.*
import org.bitcoinj.core.SegwitAddress
import org.bitcoinj.core.Transaction
import org.bitcoinj.params.RegTestParams
import org.bitcoinj.script.ScriptBuilder
import java.util.*
import kotlin.random.Random
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds


fun FrostGroup.getIndex(mid: String) = members.find { it.peer == mid }?.index
fun FrostGroup.getMidForIndex(index: Int) = members.find { it.index == index }?.peer

abstract class NetworkManager {
    val defaultScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * @return (status, amount of packets dropped)
     */
    abstract suspend fun send(peer: Peer, msg: FrostMessage): Pair<Boolean, Int>
    abstract fun getMyPeer(): Peer
    abstract fun getPeerFromMid(mid: String): Peer
    abstract fun peers(): List<Peer>
    abstract suspend fun broadcast(msg: FrostMessage, recipients: List<Peer> = listOf()): Pair<Boolean, Int>
}

sealed interface Update {
    data class KeyGenDone(val pubkey: String) : Update
    data class StartedKeyGen(val id: Long) : Update
    data class ProposedKeyGen(val id: Long) : Update
    data class BitcoinSignRequestReceived(val id: Long, val fromMid: String, val transaction: Transaction) : Update
    data class SignDone(val id: Long, val signature: String) : Update
    data class TextUpdate(val text: String) : Update
    data class TimeOut(val id: Long) : Update
}


sealed interface FrostState {
    object NotReady : FrostState {
        override fun toString(): String = "NotReady"
    }

    data class RequestedToJoin(val id: Long) : FrostState {
        override fun toString(): String = "RequestedToJoin($id)"
    }

    object ReadyForKeyGen : FrostState {
        override fun toString(): String = "ReadyForKeyGen"
    }

    data class KeyGen(val id: Long) : FrostState {
        override fun toString(): String = "KeyGen($id)"
    }

    object ReadyForSign : FrostState {
        override fun toString(): String = "ReadyForSign"
    }

    data class ProposedSign(val id: Long) : FrostState {
        override fun toString(): String = "ProposedSign($id)"
    }

    data class Sign(val id: Long) : FrostState {
        override fun toString(): String = "Sign($id)"
    }

    data class ProposedJoin(val id: Long) : FrostState {
        override fun toString(): String = "ProposedJoin($id)"
    }
}

typealias OnJoinRequestResponseCallback = (Peer, RequestToJoinResponseMessage) -> Unit
typealias KeyGenCommitmentsCallback = (Peer, KeyGenCommitments) -> Unit
typealias KeyGenShareCallback = (Peer, KeyGenShare) -> Unit
typealias SignShareCallback = (Peer, SignShare) -> Unit
typealias PreprocessCallback = (Peer, Preprocess) -> Unit
typealias SignRequestCallback = (Peer, SignRequest) -> Unit
typealias SignRequestResponseCallback = (Peer, SignRequestResponse) -> Unit


class FrostManager(
    val receiveChannel: Flow<Pair<Peer, FrostMessage>>,
    val db: FrostDatabase,
    val networkManager: NetworkManager,
    private val config: FrostManagerConfig = defaultConfig,
    val updatesChannel: MutableSharedFlow<Update> = MutableSharedFlow(extraBufferCapacity = 10),
    var state: FrostState = FrostState.ReadyForKeyGen,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    var frostInfo: FrostGroup? = null

    //        get() = getFrostInfo()
    private val msgProcessMap: MutableMap<KClass<out FrostMessage>, suspend (Peer, FrostMessage) -> Unit> =
        mutableMapOf(

        )
    var droppedMsgs = 0
        private set
    private val droppedMsgsMutex = Mutex(false)
    suspend fun addDroppedMsgs(toadd: Int) {
        droppedMsgsMutex.withLock {
            droppedMsgs += toadd
        }
    }

    lateinit var bitcoinService: BitcoinService

    fun initBitcoin(bitcoinService: BitcoinService) {
        this.bitcoinService = bitcoinService
    }

    init {
        msgProcessMap[RequestToJoinMessage::class] = { peer, msg ->
            processRequestToJoin(
                peer,
                msg as RequestToJoinMessage
            )
        }
        msgProcessMap[RequestToJoinResponseMessage::class] = { peer, msg ->
            processRequestToJoinResponse(
                peer,
                msg as RequestToJoinResponseMessage
            )
        }
        msgProcessMap[KeyGenCommitments::class] =
            { peer, msg -> processKeyGenCommitments(peer, msg as KeyGenCommitments) }
        msgProcessMap[KeyGenShare::class] = { peer, msg -> processKeyGenShare(peer, msg as KeyGenShare) }
        msgProcessMap[SignShare::class] = { peer, msg -> processSignShare(peer, msg as SignShare) }
        msgProcessMap[Preprocess::class] = { peer, msg -> processPreprocess(peer, msg as Preprocess) }
        msgProcessMap[SignRequest::class] = { peer, msg -> processSignRequest(peer, msg as SignRequest) }
        msgProcessMap[SignRequestResponse::class] = { peer, msg ->
            processSignRequestResponse(
                peer,
                msg as SignRequestResponse
            )
        }
        msgProcessMap[SignRequestBitcoin::class] = { peer,msg ->
            processSignRequestBitcoin(peer,msg as SignRequestBitcoin)
        }
    }

    var keyGenJob: Job? = null

    val signJobs = mutableMapOf<Long, Job>()

    private var agent: SchnorrAgent? = null
    val bitcoinDaoKey: ByteArray?
        get() =  agent?.keyWrapper?._bitcoin_encoded_key
    var agentSendChannel = Channel<SchnorrAgentMessage>(10)
    var agentReceiveChannel = Channel<SchnorrAgentOutput>(10)

    private var joinId = -1L

    lateinit var dbMe: Me

    val onJoinRequestResponseCallbacks = mutableMapOf<Int, OnJoinRequestResponseCallback>()
    val onKeyGenCommitmentsCallBacks = mutableMapOf<Int, KeyGenCommitmentsCallback>()
    val onKeyGenShareCallbacks = mutableMapOf<Int, KeyGenShareCallback>()
    val onPreprocessCallbacks = mutableMapOf<Int, PreprocessCallback>()
    val onSignShareCallbacks = mutableMapOf<Int, SignShareCallback>()
    val onSignRequestCallbacks = mutableMapOf<Int, SignRequestCallback>()
    val onSignRequestResponseCallbacks = mutableMapOf<Int, SignRequestResponseCallback>()
    private var cbCounter = 0

    fun addJoinRequestResponseCallback(cb: OnJoinRequestResponseCallback): Int {
        val id = cbCounter++
        onJoinRequestResponseCallbacks[id] = cb
        return id
    }

    fun addKeyGenCommitmentsCallbacks(cb: KeyGenCommitmentsCallback): Int {
        val id = cbCounter++
        onKeyGenCommitmentsCallBacks[id] = cb
        return id
    }

    fun removeKeyGenCommitmentsCallbacks(id: Int) {
        onKeyGenCommitmentsCallBacks.remove(id)
    }

    fun addKeyGenShareCallback(cb: KeyGenShareCallback): Int {
        val id = cbCounter++
        onKeyGenShareCallbacks[id] = cb
        return id
    }

    fun removeKeyGenShareCallback(id: Int) {
        onKeyGenShareCallbacks.remove(id)
    }

    fun removeJoinRequestResponseCallback(id: Int) {
        onJoinRequestResponseCallbacks.remove(id)
    }

    fun addSignShareCallback(cb: SignShareCallback): Int {
        val id = cbCounter++
        onSignShareCallbacks[id] = cb
        return id
    }

    fun removeSignShareCallback(id: Int) {
        onSignShareCallbacks.remove(id)
    }

    fun addPreprocessCallabac(cb: PreprocessCallback): Int {
        val id = cbCounter++
        onPreprocessCallbacks[id] = cb
        return id
    }

    fun removePreprocessCallback(id: Int) {
        onPreprocessCallbacks.remove(id)
    }

    fun addOnSignRequestCallback(cb: SignRequestCallback): Int {
        val id = cbCounter++
        onSignRequestCallbacks[id] = cb
        return id
    }

    fun addOnSignRequestResponseCallbac(cb: SignRequestResponseCallback): Int {
        val id = cbCounter++
        onSignRequestResponseCallbacks[id] = cb
        return id
    }

    init {
        scope.launch {
            receiveChannel
                .collect {
                    processMsg(it)
                }
        }

        scope.launch(Dispatchers.Default) {
            val storedMe = db.meDao().get()
            dbMe = Me(
                -1,
                byteArrayOf(0), 0, 1, 1, listOf("")
            )
            if (storedMe != null){
                agent = SchnorrAgent(storedMe.frostKeyShare,storedMe.frostN,storedMe.frostIndex,storedMe.frostThresholod, agentSendChannel, agentReceiveChannel)
                dbMe = storedMe
//                delay(5000)
                frostInfo = FrostGroup(
                    members = storedMe.frostMembers.map {
                        val (mid, indexstr) = it.split("#").take(2)
                        FrostMemberInfo(
                           mid,
                            indexstr.toInt()
                        )
                    },
                    threshold = dbMe.frostThresholod,
                    myIndex = dbMe.frostIndex
                )
                state = FrostState.ReadyForSign
            }else{
                dbMe = Me(
                    -1,
                    byteArrayOf(0),0,1,1, listOf("")
                )
            }
        }

    }

    sealed interface SignParams {
        data class Test(val data: ByteArray) : SignParams
        data class Bitcoin(
            val transaction: Transaction
        ) : SignParams
    }

    suspend fun proposeSignAsync(signParams: SignParams): Pair<Boolean, Long> {
        // we want to make multiple props at the same time?
        if (state !is FrostState.ReadyForSign) {
            return false to 0
        }

        val signId = Random.nextLong()
//        state = FrostState.ProposedSign(signId)

        scope.launch {
            var responseCounter = 0
            val mutex = Mutex(true)
            val participatingIndices = mutableListOf<Int>()
            val callbacId = addOnSignRequestResponseCallbac { peer, signRequestResponse ->
                if (signRequestResponse.id != signId) {
                    return@addOnSignRequestResponseCallbac
                }
                if (signRequestResponse.ok)
                    responseCounter += 1
                participatingIndices.add(
                    frostInfo?.getIndex(peer.mid)
                        ?: error(" FrostInfo is null. This is a bug. Maybe you are trying to sign without having first joined a group")
                )
                if (responseCounter >= frostInfo!!.threshold - 1) {
                    mutex.unlock()
                }
            }
            val signRequest = when (signParams) {
                is SignParams.Test -> SignRequest(signId, signParams.data)
                is SignParams.Bitcoin -> SignRequestBitcoin(
                    signId,
                    signParams.transaction.bitcoinSerialize()
                )
            }
            val (broadcastOk, amountDropped) = networkManager.broadcast(signRequest)
            addDroppedMsgs(amountDropped)
            if (!broadcastOk) {
                updatesChannel.emit(Update.TimeOut(signId))
                return@launch
            }

            val enoughResponsesReceived = withTimeoutOrNull(config.waitForSignResponseTimeout) {
                mutex.lock()// make sure that enough peeps are available
            }

            if (enoughResponsesReceived == null) {
                updatesChannel.emit(Update.TimeOut(signId))
                return@launch
            }

            onSignRequestResponseCallbacks.remove(callbacId)

            Log.d("FROST", "started sign")

            val agentSendChannel = Channel<SchnorrAgentMessage>(1)
            val agentReceiveChannel = Channel<SchnorrAgentOutput>(1)

            signJobs[signId] = startSign(
                signId, signParams,
                agentSendChannel, agentReceiveChannel,
                true,
                (participatingIndices.plus(
                    frostInfo?.myIndex
                        ?: error(" FrostInfo is null. This is a bug. Maybe you are trying to sign without having first joined a group")
                ))
            )
        }


        return true to signId
    }

    suspend fun acceptProposedSign(id: Long, fromMid: String, signParams: SignParams) {
        val (receivedAc, amountDropped) = networkManager.send(
            networkManager.getPeerFromMid(fromMid),
            SignRequestResponse(id, true)
        )
        addDroppedMsgs(amountDropped)
        if (!receivedAc) {
            updatesChannel.emit(Update.TimeOut(id))
            return
        }
        val agentSendChannel = Channel<SchnorrAgentMessage>(1)
        val agentReceiveChannel = Channel<SchnorrAgentOutput>(1)

        //todo wait for a msg here?

        signJobs[id] = startSign(
            id,
            signParams,
            agentSendChannel, agentReceiveChannel
        )
    }


    private suspend fun startSign(
        signId: Long,
        signParams: SignParams,
        agentSendChannel: Channel<SchnorrAgentMessage>,
        agentReceiveChannel: Channel<SchnorrAgentOutput>,
        isProposer: Boolean = false,
        participantIndices: List<Int> = listOf(),
    ) = scope.launch {
        state = FrostState.Sign(signId)

        val mutex = Mutex(true)

        // whether we received a preprocess msg from the initiator
        // this signals the other peers to start
        val participantIndices = participantIndices.toMutableList()

        val signShareCbId = addSignShareCallback { peer, msg ->
            if (msg.id != signId)
                return@addSignShareCallback
            launch {
                agentSendChannel.send(
                    SchnorrAgentOutput.SignShare(
                        msg.bytes,
                        frostInfo?.getIndex(peer.mid)!!
                    )
                )
            }
        }
        val preprocessCbId = addPreprocessCallabac { peer, preprocess ->
            if (preprocess.id != signId) {
                return@addPreprocessCallabac
            }
            launch {
                if (!isProposer && mutex.isLocked && preprocess.participants.isNotEmpty()) { // only the init message has size > 0
                    mutex.unlock()
                    participantIndices.addAll(preprocess.participants)
                }
                agentSendChannel.send(
                    SchnorrAgentOutput.SignPreprocess(
                        preprocess.bytes,
                        frostInfo?.getIndex(peer.mid)!!,
                    )
                )
            }
        }

        fun fail() {
            Log.d("FROST", "failing sign")
            state =
                FrostState.ReadyForSign
            removePreprocessCallback(preprocessCbId)
            removeSignShareCallback(signShareCbId)
            scope.launch {
                updatesChannel.emit(Update.TimeOut(signId))
            }
            cancel()
        }

        val (sendSemaphore, semaphoreMaxPermits) = if (isProposer) {
            Semaphore(participantIndices.size) to participantIndices.size
        } else {
            //at this point we don't now the amount of participants, so use amount of peers
            Semaphore(networkManager.peers().size) to networkManager.peers().size
        }
        launch {
            for (output in agentReceiveChannel) {
                when (output) {
                    is SchnorrAgentOutput.SignPreprocess -> launch {
                        sendSemaphore.acquire()
                        val ok = sendToParticipants(
                            participantIndices,
                            Preprocess(
                                signId,
                                output.preprocess,
                                if (isProposer) {
                                    participantIndices
                                } else {
                                    listOf()
                                }
                            )
                        )
                        sendSemaphore.release()
                        if (!ok)
                            fail()
                    }

                    is SchnorrAgentOutput.SignShare -> {
                        sendSemaphore.acquire()
                        val ok = sendToParticipants(participantIndices, SignShare(signId, output.share))
                        sendSemaphore.release()
                        if (!ok)
                            fail()
                    }

                    is SchnorrAgentOutput.Signature -> updatesChannel.emit(
                        Update.SignDone(
                            signId,
                            output.signature.toHex()
                        )
                    )

                    else -> {}
                }
            }
        }
        if (!isProposer) {
            // wait for initial msg to signal that we can start
            val lockReceived = withTimeoutOrNull(config.waitForInitialPreprocessTimeout) {
                mutex.lock()
            }

            if (lockReceived == null) {
                fail()
            }
        }

        val signDone = withTimeoutOrNull(config.signTimeout) {
            val toSign = if(signParams is SignParams.Test) signParams.data else null
            val bitcoinParams = if (signParams is SignParams.Bitcoin){
                // I gues when you serialize the tx you lose some info?
                val inputWithVals =bitcoinService.getTrackedOutputs()
                    .find { it.parentTransactionHash ==  signParams.transaction.inputs[0].outpoint.hash && it.index ==  signParams.transaction.inputs[0].outpoint.index.toInt()}
                    ?: error("we do not know about this output")
                val tx = Transaction(bitcoinService.networkParams)
                tx.addInput(inputWithVals.parentTransactionHash, inputWithVals.index.toLong(), ScriptBuilder.createOutputScript(
                    SegwitAddress.fromProgram(RegTestParams.get(),1, agent!!.keyWrapper._bitcoin_encoded_key)))
                tx.addOutput(signParams.transaction.outputs[0])

                SchnorrAgent.BitcoinParams(tx, inputWithVals.value.value)
            } else null
            agent!!.startSigningSession(signId.toInt(), toSign, bitcoinParams, agentSendChannel, agentReceiveChannel)
        }
        if (signDone == null) {
            fail()
        }
        while (sendSemaphore.availablePermits != semaphoreMaxPermits) {
            delay(1000)
        }
        state = FrostState.ReadyForSign
        cancel()

    }

    private suspend fun startKeyGen(id: Long, midsOfNewGroup: List<String>, isNew: Boolean = false) = scope.launch {
        joinId = id

        agentSendChannel = Channel(10)
        agentReceiveChannel = Channel(10)

        val amount = midsOfNewGroup.size
        val midsOfNewGroup = midsOfNewGroup
            .sorted()
        Log.d("FROST", "new group size : ${midsOfNewGroup.size}")
        val getIndex = { mid: String ->
            midsOfNewGroup.indexOf(mid) + 1
        }
        val getMidFromIndex = { index: Int ->
            midsOfNewGroup[index - 1]
        }

        val index = getIndex(networkManager.getMyPeer().mid)

        agent = SchnorrAgent(amount, index, midsOfNewGroup.size / 2 + 1, agentSendChannel, agentReceiveChannel)

        val mutex = Mutex(true)
        val commitmentCbId = addKeyGenCommitmentsCallbacks { peer, msg ->
            launch {
                if (!isNew && mutex.isLocked)
                    mutex.unlock()
                agentSendChannel.send(SchnorrAgentMessage.KeyCommitment(msg.byteArray, getIndex(peer.mid)))
            }
        }
        val shareCbId = addKeyGenShareCallback { peer, keyGenShare ->
            launch {
                agentSendChannel.send(SchnorrAgentMessage.DkgShare(keyGenShare.byteArray, getIndex(peer.mid)))
            }
        }

        fun fail() {
            state = if (isNew) {
                FrostState.ReadyForKeyGen
            } else {
                FrostState.ReadyForSign
            }
            removeKeyGenCommitmentsCallbacks(commitmentCbId)
            removeKeyGenShareCallback(shareCbId)
            //todo timed out/ messaged dropped same thing?
            scope.launch {
                updatesChannel.emit(Update.TimeOut(id))
            }
            cancel()
        }

        val semaphoreMaxPermits = midsOfNewGroup.size
        val sendSemaphore = Semaphore(semaphoreMaxPermits)
        val doneSignal = Mutex(true)
        launch {
            for (agentOutput in agentReceiveChannel) {
//                Log.d("FROST", "sending $agentOutput")
                when (agentOutput) {
                    is SchnorrAgentOutput.DkgShare -> {
                        scope.launch {
                            sendSemaphore.acquire()
                            val (ok, amountDropped) = networkManager.send(
                                networkManager.getPeerFromMid(getMidFromIndex(agentOutput.forIndex)),
                                KeyGenShare(joinId, agentOutput.share)
                            )
                            addDroppedMsgs(amountDropped)
                            sendSemaphore.release()
                            if (!ok)
                                fail()

                        }
                    }

                    is SchnorrAgentOutput.KeyCommitment -> {
                        scope.launch {
                            sendSemaphore.acquire()
                            val (ok, amountDropped) = networkManager.broadcast(
                                KeyGenCommitments(
                                    joinId,
                                    agentOutput.commitment
                                )
                            )
                            addDroppedMsgs(amountDropped)
                            sendSemaphore.release()
                            if (!ok)
                                fail()
                        }
                    }

                    is SchnorrAgentOutput.KeyGenDone -> {
                        updatesChannel.emit(Update.KeyGenDone(agentOutput.pubkey.toHex()))
                        doneSignal.unlock()
                    }

                    else -> {
                        error("RCEIVED OUTPUT FOR SIGNING WHILE DOING KEYGEN. SHOULD NOT HAPPEN")
                    }
                }
            }
        }
        if (!isNew) {
            val receivedSignal = withTimeoutOrNull(config.waitForKeygenResponseTimeout) {
                mutex.lock()
            }
            // we did not receive signal to start before timeout
            if (receivedSignal == null) {
                fail()
            }
        }
        val keygenDone = withTimeoutOrNull(config.keygenTimeout) {
            agent!!.startKeygen()
        }

        //timeout without being done
        if (keygenDone == null) {
            fail()
        }

        // use mutex to signal that last we received the last FROST message
        // without this, we could not receive that message in this function, and then the ui won't be updated
        // todo clean it up. We can probably make it so the signal is not necessary
        doneSignal.lock()

        // this waits until we sent all our messages and have received acks for them.
        //todo maybe this needs a timeout
        while (sendSemaphore.availablePermits != semaphoreMaxPermits) {
            delay(1000)
        }
        this@FrostManager.frostInfo = FrostGroup(
            (midsOfNewGroup.filter { it != networkManager.getMyPeer().mid }).map {
                FrostMemberInfo(
                    it,
                    getIndex(it)
                )
            },
            index,
            threshold = midsOfNewGroup.size / 2 + 1
        )

        Log.d("FROST","frost info is set")

        dbMe = dbMe.copy(
            frostKeyShare = agent!!.keyWrapper.serialize(),
            frostMembers = midsOfNewGroup.filter { it != networkManager.getMyPeer().mid }.map {
                "$it#${frostInfo!!.getIndex(it)}"
            },
            frostN = midsOfNewGroup.size,
            frostIndex = index,
            frostThresholod = midsOfNewGroup.size / 2 + 1
        )

        db.meDao()
            .insert(dbMe)

        state = FrostState.ReadyForSign
        //cancel when done
        cancel()


    }

    suspend fun joinGroup(peer: Peer) {
        joinId = Random.nextLong()
        if (state != FrostState.ReadyForKeyGen) {
            return
        }
        state = FrostState.ProposedJoin(joinId)
        updatesChannel.emit(Update.ProposedKeyGen(joinId))

        // start waiting for responses before sending msg
        val peersInGroupAsync = scope.async {
            withTimeoutOrNull(config.waitForKeygenResponseTimeout) {
                waitForJoinResponse(joinId)
            }
        }
        val okDeferred = scope.async(Dispatchers.Default) {
            // delay to start waiting before sending msg
            delay(10)
            networkManager.send(peer, RequestToJoinMessage(joinId))
        }

        val (ok, amountDropped) = okDeferred.await()
        addDroppedMsgs(amountDropped)

        val peersInGroup = peersInGroupAsync.await()

        // in this case, we have not received enough confirmations of peers before timing out.
        // or messages were dropped
        if (peersInGroup == null || !ok) {
            scope.launch {
                updatesChannel.emit(Update.TimeOut(joinId))
            }
            state = FrostState.ReadyForKeyGen
            return
        }
        state = FrostState.KeyGen(joinId)
        keyGenJob = startKeyGen(joinId, (peersInGroup + networkManager.getMyPeer()).map { it.mid }, true)
        Log.i("FROST", "started keygen")
    }

    private suspend fun waitForJoinResponse(id: Long): List<Peer> {
        var counter = 0
        val mutex = Mutex(true)
        val peers = mutableListOf<Peer>()
        var amount: Int? = null
        val cbId = addJoinRequestResponseCallback { peer, msg ->
            if (msg.id == id) {
                if (amount == null) {
                    amount = msg.amountOfMembers
                }
                peers.add(peer)
                counter++
                if (counter == amount)
                    mutex.unlock()
            }
        }
        mutex.lock()
        removeJoinRequestResponseCallback(cbId)
        return peers
    }

    private suspend fun processMsg(pair: Pair<Peer, FrostMessage>) {
        val (peer, msg) = pair
        Log.d("FROST", "received msg $msg")
        msgProcessMap[msg::class]?.let { it(peer, msg) }

    }

    private suspend fun processRequestToJoin(peer: Peer, msg: RequestToJoinMessage) {
        when (state) {
            FrostState.ReadyForKeyGen, FrostState.ReadyForSign -> {
                state = FrostState.KeyGen(msg.id)
                scope.launch {
                    updatesChannel.emit(Update.StartedKeyGen(msg.id))
                    // if this is the message from the "orignator"
                    if (msg.orignalMid == null) {
                        if (frostInfo != null) {
                            val ok = sendToParticipants(
                                frostInfo!!.members.map { it.index },
                                RequestToJoinMessage(msg.id, orignalMid = peer.mid)
                            )
                            if (!ok) {
                                Log.d("FROST", "Sending request to join to frost members failed")
                                return@launch
                            }

                        }
                        networkManager.send(
                            peer, RequestToJoinResponseMessage(
                                msg.id, true, frostInfo?.amount ?: 1,
                                /*(frostInfo?.members?.map { it.peer }
                                    ?.plus(networkManager.getMyPeer().mid))
                                    ?: listOf(
                                        networkManager.getMyPeer().mid
                                    )*/
                            )
                        )
                        keyGenJob = startKeyGen(msg.id,
                            frostInfo?.members?.map(FrostMemberInfo::peer)?.plus(peer.mid)
                                ?.plus(networkManager.getMyPeer().mid)
                                ?: (listOf(networkManager.getMyPeer()) + peer).map { it.mid }
                        )
                    } else {
                        // in this, we know that we arre in a frost group
                        networkManager.send(
                            networkManager.getPeerFromMid(msg.orignalMid), RequestToJoinResponseMessage(
                                msg.id, true, frostInfo?.amount ?: 1,
                                /*(frostInfo?.members?.map { it.peer }
                                    ?.plus(networkManager.getMyPeer().mid))
                                    ?: listOf(
                                        networkManager.getMyPeer().mid
                                    )*/
                            )
                        )
                        keyGenJob = startKeyGen(
                            msg.id,
                            frostInfo?.members?.map(FrostMemberInfo::peer)?.plus(msg.orignalMid)
                                ?.plus(networkManager.getMyPeer().mid)
                                ?: error("we are not in a frostGroup, yet some thinks that we are")
                        )
                    }


                }
            }

            else -> {
                // log cannot do this while in this state?
                // Maybe I should send a message bac to indicate this?
                // Actually I probably should
                networkManager.send(peer, RequestToJoinResponseMessage(msg.id, false, 0))
            }
        }
    }

    private suspend fun sendToParticipants(participantIndices: List<Int>, frostMessage: FrostMessage): Boolean {
        val participantPeers = (participantIndices - (frostInfo?.myIndex ?: error("frostinfo is null. this is a bug.")))
            .map {
                networkManager.getPeerFromMid(frostInfo?.getMidForIndex(it) ?: error("frostinfo null, this is a bug"))
            }

        val (ok, amountDropped) = networkManager.broadcast(frostMessage, participantPeers)
        addDroppedMsgs(amountDropped)
        return ok
    }

    private fun processRequestToJoinResponse(peer: Peer, msg: RequestToJoinResponseMessage) {
        onJoinRequestResponseCallbacks.forEach {
            it.value(peer, msg)
        }
    }

    private fun processKeyGenCommitments(peer: Peer, msg: KeyGenCommitments) {
        onKeyGenCommitmentsCallBacks.forEach {
            it.value(peer, msg)
        }
    }

    private fun processKeyGenShare(peer: Peer, msg: KeyGenShare) {
        onKeyGenShareCallbacks.forEach {
            it.value(peer, msg)
        }
    }

    private fun processSignShare(peer: Peer, msg: SignShare) {
        onSignShareCallbacks.forEach {
            it.value(peer, msg)
        }
    }

    private fun processPreprocess(peer: Peer, msg: Preprocess) {
        onPreprocessCallbacks.forEach {
            it.value(peer, msg)
        }
    }

    private fun processSignRequest(peer: Peer, msg: SignRequest) {
        onSignRequestCallbacks.forEach {
            it.value(peer, msg)
        }
        when (state) {
            FrostState.ReadyForSign -> {
                scope.launch {
//                    updatesChannel.emit(Update.SignRequestReceived(msg.id, peer.mid, msg.data))
                }
            }

            else -> {}
        }
    }

    private fun processSignRequestBitcoin(peer: Peer, msg: SignRequestBitcoin){
        when (state) {
            FrostState.ReadyForSign -> {
                scope.launch {
                    updatesChannel.emit(Update.BitcoinSignRequestReceived(msg.id,peer.mid,Transaction(bitcoinService.networkParams, msg.tx)))
                }
            }

            else -> {}
        }
    }

    private fun processSignRequestResponse(peer: Peer, msg: SignRequestResponse) {
        onSignRequestResponseCallbacks.forEach {
            it.value(peer, msg)
        }
    }

    companion object {
        val defaultConfig = FrostManagerConfig(
            signTimeout = (10 * 60 * 1000).milliseconds,
            waitForSignResponseTimeout = (5 * 60 * 1000).milliseconds,
            keygenTimeout = (10 * 60 * 1000).milliseconds,
            waitForKeygenResponseTimeout = (5 * 60 * 1000).milliseconds,
            waitForInitialPreprocessTimeout = (5 * 60 * 1000).milliseconds
        )
    }

}

data class FrostManagerConfig(
    val signTimeout: Duration,
    val waitForSignResponseTimeout: Duration,
    val keygenTimeout: Duration,
    val waitForKeygenResponseTimeout: Duration,
    val waitForInitialPreprocessTimeout: Duration
)
