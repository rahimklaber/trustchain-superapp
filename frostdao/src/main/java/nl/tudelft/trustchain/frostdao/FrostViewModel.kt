package nl.tudelft.trustchain.frostdao

import android.util.Log
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import nl.tudelft.ipv8.util.hexToBytes
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.trustchain.frostdao.bitcoin.BitcoinService
import nl.tudelft.trustchain.frostdao.ipv8.FrostCommunity
import nl.tudelft.trustchain.frostdao.ipv8.FrostManager
import nl.tudelft.trustchain.frostdao.ipv8.FrostState
import nl.tudelft.trustchain.frostdao.ipv8.Update
import nl.tudelft.trustchain.frostdao.ipv8.message.RequestToJoinMessage
import nl.tudelft.trustchain.frostdao.ipv8.message.SignRequest
import nl.tudelft.trustchain.frostdao.ipv8.message.SignRequestBitcoin
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionWitness
import java.util.*

enum class ProposalState {
    Done,
    Started,
    Rejected,
    Cancelled,
    TimedOut
}

sealed interface Proposal{
    val id: Long
    val fromMid: String
    var state: ProposalState
    fun type() : String
}


data class SignProposal(
    override val id: Long,
    override val fromMid: String,
    val msg: ByteArray,
    var signed: Boolean = false,
    var signatureHex: String = "",
    override var state: ProposalState = ProposalState.Started
) : Proposal {
    override fun type(): String = "Sign"
}

data class BitcoinProposal(
    override val id: Long,
    override val fromMid: String,
    val transaction: Transaction,
    var done: Boolean = false,
    override var state: ProposalState = ProposalState.Started

) : Proposal {
    override fun type(): String = "Bitcoin"
}

data class JoinProposal(
    override val id: Long,
    override val fromMid: String,
    override var state: ProposalState
) : Proposal {
    override fun type(): String = "Join"

}


enum class FrostPeerStatus(val color: Color) {
    Pending(Color.Yellow),
    Available(Color.Green),
    NonResponsive(Color.Red)
}




class FrostViewModel(
    val frostCommunity: FrostCommunity,
    val frostManager: FrostManager,
    val bitcoinService: BitcoinService,
    val toastMaker : (String) -> Unit,
) : ViewModel() {
    var state by mutableStateOf(frostManager.state)
    var index by mutableStateOf(frostManager.frostInfo?.myIndex)
    var amountOfMembers by mutableStateOf(frostManager.frostInfo?.amount)
    var threshold by mutableStateOf(frostManager.frostInfo?.threshold)
    var amountDropped by mutableStateOf(frostManager.droppedMsgs)

    private var _peers = mutableStateOf<List<String>>(listOf())
    val peers by _peers

    //todo figure out how to hide this from consumers and make it a non-mutable list
    val proposals = mutableStateListOf<Proposal>()
    val myProposals = mutableStateListOf<Proposal>()
    //status of other peers in the community
    val stateMap = mutableStateMapOf<String, FrostPeerStatus>()
    //unix time when we last heard from
    val lastHeardFrom = mutableStateMapOf<String,Long>()


    var bitcoinNumPeers by mutableStateOf(0)
    var bitcoinBalance by mutableStateOf("0")
    var bitcoinAddress by mutableStateOf<String?>(null)

    var bitcoinDaoAddress by mutableStateOf<Address?>(null)
    var bitcoinDaoBalance by mutableStateOf<String?>(null)
    init {
        frostManager.initBitcoin(bitcoinService)
        viewModelScope.launch(Dispatchers.Default) {
            launch {
                while(true){
                    delay(5000)
                    if (bitcoinService.kit.isRunning){

                        val amountOfPeers = bitcoinService.kit.peerGroup().numConnectedPeers()
                        Log.d("FROST","Bitcoin address ${bitcoinService.personalAddress}")
                        if (bitcoinAddress == null){
                            bitcoinAddress = bitcoinService.personalAddress.toString()
                        }
                        bitcoinNumPeers = amountOfPeers
                        bitcoinBalance = bitcoinService.kit.wallet().balance.toFriendlyString()
                        bitcoinDaoAddress?.let {
                            bitcoinDaoBalance = bitcoinService.getBalanceForTrackedAddress(it).toFriendlyString()
                        }
                    }

                }
            }
            launch {
                while (true){
                    delay(5000)
                    refreshFrostData()
                    _peers.value = frostCommunity.getPeers().map { it.mid }

                    val now = Date()
                    var allPeerMids = _peers.value union  (frostManager.frostInfo?.members?.map{it.peer} ?: listOf())
                    _peers.value = allPeerMids.toList()
                    for (mid in allPeerMids)  {
                        val lastResponseTimeMillis = frostCommunity.lastResponseFrom[mid]?.time
                        if (lastResponseTimeMillis != null) {
                            lastHeardFrom[mid] = lastResponseTimeMillis /1000
                        }
                        if (frostManager.frostInfo != null && frostManager.frostInfo!!.members.find {
                                it.peer == mid
                            } == null){
                            stateMap[mid] = FrostPeerStatus.Pending
                        }
                        else if (lastResponseTimeMillis == null){
                            stateMap[mid] = FrostPeerStatus.NonResponsive
                        }else if(now.time  -lastResponseTimeMillis  < 60_000){
                            stateMap[mid] = FrostPeerStatus.Available
                        }else{
                            stateMap[mid] = FrostPeerStatus.NonResponsive

                        }

                    }

                }
            }
            launch {
                frostCommunity
                    .channel
                    .filter {
                        it.second is RequestToJoinMessage || it.second is SignRequest
                    }
                    .collect{
                        when(it.second){
                            is RequestToJoinMessage -> {
//                                proposals.add(JoinProposal(it.first.mid))
                            }
                            is SignRequest -> {
                                proposals.add(SignProposal((it.second as SignRequest).id,it.first.mid, (it.second as SignRequest).data))
                            }
                            is SignRequestBitcoin -> {
                                //todo
                                proposals.add(BitcoinProposal(it.second.id,it.first.mid, Transaction(bitcoinService.networkParams, (it.second as SignRequestBitcoin).tx)))
                            }
                            else -> Unit
                        }
                    }
            }
            frostManager.updatesChannel.collect{
                Log.d("FROST","received msg in viewmodel : $it")
                when(it){
                    is Update.KeyGenDone -> {
                        refreshFrostData()
                        bitcoinDaoAddress = bitcoinService.taprootPubKeyToAddress(it.pubkey)
                        bitcoinDaoAddress?.let(bitcoinService::trackAddress)
                        Log.d("FROST","Bitcoin DAO address: $bitcoinDaoAddress")
                    }
                    is Update.StartedKeyGen, is Update.ProposedKeyGen-> {
                        refreshFrostData()
                    }
                    is Update.TextUpdate -> {

                    }
                    is Update.SignRequestReceived -> {
                        val prop = SignProposal(it.id,it.fromMid,it.data)
                        val found = proposals.find {checkprop ->
                            checkprop is SignProposal && checkprop.id == prop.id
                        }
                        if (found == null){
                            proposals.add(prop)
                        }
                    }
                    is Update.BitcoinSignRequestReceived -> {
                        val prop = BitcoinProposal(it.id, it.fromMid, it.transaction)
                        val found = proposals.find {checkprop ->
                            checkprop is BitcoinProposal && checkprop.id == prop.id
                        }
                        if (found == null){
                            proposals.add(prop)
                        }
                    }
                    is Update.SignDone -> {
                        refreshFrostData()
                        val update = it
                        val foundInMyProps = myProposals.find { prop ->
                                prop.id == update.id
                        }
                        if (foundInMyProps is SignProposal){
                            foundInMyProps.signatureHex = update.signature
                            foundInMyProps.signed = true
                            return@collect
                        }else if(foundInMyProps is BitcoinProposal){
                            foundInMyProps.done = true
                            foundInMyProps.transaction.inputs[0].witness =TransactionWitness(1).also { witness ->
                               witness.setPush(0,it.signature.hexToBytes())
                            }
                            Log.d("Bitcoin", "Signed tx: ${foundInMyProps.transaction.bitcoinSerialize().toHex()}")
                            toastMaker("SIGNED BITCOIN")
                            return@collect
                        }

                        val foundInProps = proposals.find { prop ->
                                prop.id == update.id
                        }

                        if (foundInProps is SignProposal){
                            foundInProps.signatureHex = update.signature
                            foundInProps.signed = true
                        }else if(foundInProps is BitcoinProposal){
                            foundInProps.done = true
                            toastMaker("SIGNED BITCOIN")
                        }
                    }
                    is Update.TimeOut -> {
                        refreshFrostData()
                        toastMaker("action with ${it.id} timed out")
                        Log.d("FROST","Timed out action with id ${it.id}")
                    }
                }
            }
        }
    }

    fun refreshFrostData(){
        state = frostManager.state
        index = frostManager.frostInfo?.myIndex
        amountOfMembers = frostManager.frostInfo?.amount
        threshold = frostManager.frostInfo?.threshold
        amountDropped = frostManager.droppedMsgs
    }

    /**
     * request to join a peer that is active
     */
    suspend fun joinFrost(){
        Log.d("FROST","joingroup function called ")
        val currTime = Date().time/1000
        val peer = lastHeardFrom.filter {
            // peers that we heard from at most 60 secs ago
            it.value + 60 > currTime
        }.firstNotNullOfOrNull { frostManager.networkManager.getPeerFromMid(it.key) } ?: return
        Log.d("FROST","Joining group ")
        frostManager.joinGroup(
            peer
        )
    }

    suspend fun proposeSign(data: ByteArray){
        val (ok, id) = frostManager.proposeSignAsync(FrostManager.SignParams.Test(data))
        if (!ok){
            Log.d("FROST", "Failed to create sign proposal")
            return
        }
        myProposals.add(SignProposal(id, frostCommunity.myPeer.mid, data))

    }

    suspend fun proposeSignBitcoin(amount: Long, recipient: String){
        if(bitcoinDaoAddress == null){
            toastMaker("Bitcoin Dao address not available.")
            return
        }
        val tx = bitcoinService.createSendTransactionForDaoAccount(bitcoinDaoAddress!!, Coin.valueOf(amount), Address.fromString(bitcoinService.networkParams,recipient))
        if (tx.isSuccess){
            val (ok, id) = frostManager.proposeSignAsync(FrostManager.SignParams.Bitcoin(tx.getOrThrow()))
        }else{
            toastMaker("could not create bitcoin transaction")
        }
    }

    suspend fun acceptSign(id: Long){
        val prop = proposals
            .find {
                it is BitcoinProposal && it.id == id
            } as BitcoinProposal?

        if(prop == null){
            Log.d("FROST", "cold not accept sign proposal. We could not find a proposal with this id")
            return
        }

        when (val x = lastHeardFrom[prop.fromMid]){
            null -> {
                toastMaker("Proposer of request is offline! Not accepting.")
                return
            }
            else  -> {
                // don't do anything if we haven't heard from the other peer in 2 minutes
                if((Date().time / 1000) - x > 120){
                    toastMaker("We have not heard from peer in 2 minutes. Not sending accept message.")
                    return
                }
            }
        }

        frostManager.acceptProposedSign(prop.id,prop.fromMid,FrostManager.SignParams.Bitcoin(prop.transaction))
    }

    // remove ongong actions
    suspend fun panic(){
       frostManager.keyGenJob?.cancel()
        frostManager.signJobs.forEach {
            it.value.cancel()
        }
        frostManager.onJoinRequestResponseCallbacks.clear()
        frostManager.onKeyGenCommitmentsCallBacks.clear()
        frostManager.onKeyGenShareCallbacks.clear()
        frostManager.onPreprocessCallbacks.clear()
        frostManager.onSignShareCallbacks.clear()
        frostManager.onSignRequestCallbacks.clear()
        frostManager.onSignRequestResponseCallbacks.clear()

        if (frostManager.frostInfo == null){
            frostManager.state = FrostState.ReadyForKeyGen
        }else{
            frostManager.state = FrostState.ReadyForSign
        }
    }
}
