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
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionWitness
import org.bitcoinj.wallet.Wallet
import java.util.*

enum class ProposalState {
    Done,
    Proposed,
    Started,
    Rejected,
    Cancelled,
    TimedOut
}

sealed interface Proposal{
    val id: Long
    val fromMid: String
    var state: MutableState<ProposalState>
    fun type() : String
}


data class BitcoinProposal(
    override val id: Long,
    override val fromMid: String,
    val transaction: Transaction,
    override var state: MutableState<ProposalState> = mutableStateOf(ProposalState.Proposed),
    ) : Proposal {
    val started = derivedStateOf { state.value != ProposalState.Proposed}
    val done = derivedStateOf { state.value == ProposalState.Done || state.value == ProposalState.Cancelled || state.value == ProposalState.Rejected}
    override fun type(): String = "Bitcoin"
}
//todo: I will need to change this if I decide to send the unused Bitcoin back to the DAO.
fun BitcoinProposal.BtcAmount(): Coin{
    return transaction.outputSum
}
fun BitcoinProposal.recipient(networkParams: NetworkParameters): Address? {
    return transaction.outputs.firstOrNull()?.scriptPubKey?.getToAddress(networkParams)
}


enum class FrostPeerStatus(val color: Color) {
    Pending(Color.Yellow),
    Available(Color.Green),
    NonResponsive(Color.Red)
}




class FrostViewModel(
    private val frostCommunity: FrostCommunity,
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
            delay(2222)
            if(frostManager.state is FrostState.ReadyForSign){
                val pubKey = frostManager.bitcoinDaoKey ?: return@launch
                bitcoinDaoAddress = bitcoinService.taprootPubKeyToAddress(pubKey.toHex())
                bitcoinDaoAddress?.let(bitcoinService::trackAddress)
            }
        }
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
                        bitcoinBalance = bitcoinService.kit.wallet().getBalance(Wallet.BalanceType.AVAILABLE_SPENDABLE).toFriendlyString()
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
                        if(foundInMyProps is BitcoinProposal){
                            foundInMyProps.state.value = ProposalState.Done
                            foundInMyProps.transaction.inputs[0].witness =TransactionWitness(1).also { witness ->
                               witness.setPush(0,it.signature.hexToBytes())
                            }
                            bitcoinService.kit.peerGroup().broadcastTransaction(foundInMyProps.transaction)

                            Log.d("FROST", "Signed tx: ${foundInMyProps.transaction.bitcoinSerialize().toHex()}")
                            toastMaker("SIGNED BITCOIN")
                            return@collect
                        }

                        val foundInProps = proposals.find { prop ->
                                prop.id == update.id
                        }

                        if(foundInProps is BitcoinProposal){
                            foundInProps.state.value = ProposalState.Done
                            toastMaker("SIGNED BITCOIN")
                        }
                    }
                    is Update.TimeOut -> {
                        if(state is FrostState.ProposedJoin || state is FrostState.Sign){
                            toastMaker("Signing timed out")
                        }else if (state is FrostState.ProposedJoin || state is FrostState.KeyGen){
                            toastMaker("Key Generation timed out")
                        }
                        refreshFrostData()

                        Log.d("FROST","Timed out action with id ${it.id}")
                    }

                    is Update.NotEnoughVotes -> {
                        toastMaker("Too many members reject proposal.")
                        findBitcoinProposalInProposedByMe(it.id)?.let { proposal ->
                            proposal.state.value = ProposalState.Cancelled
                        }
                    }
                    is Update.Rejected -> {
                        findBitcoinProposalInReceived(it.id)?.let { proposal ->
                            proposal.state.value = ProposalState.Rejected
                        }
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


    suspend fun proposeSignBitcoin(amount: Long, recipient: String){
        if(bitcoinDaoAddress == null){
            toastMaker("Bitcoin Dao address not available.")
            return
        }
        val tx = bitcoinService.createSendTransactionForDaoAccount(bitcoinDaoAddress!!, Coin.valueOf(amount), Address.fromString(bitcoinService.networkParams,recipient))
        if (tx.isSuccess){
            val (ok, id) = frostManager.proposeSignAsync(FrostManager.SignParams.Bitcoin(tx.getOrThrow()))
            if(ok){
                myProposals.add(BitcoinProposal(id,frostCommunity.myPeer.mid,tx.getOrThrow()))
            }else{
                toastMaker("Creating proposal failed")
            }
        }else{
            toastMaker("could not create bitcoin transaction")
        }
    }

    fun findBitcoinProposalInReceived(id:Long): BitcoinProposal? =
        proposals
        .find {
            it is BitcoinProposal && it.id == id
        } as BitcoinProposal?

    fun findBitcoinProposalInProposedByMe(id:Long): BitcoinProposal? =
        myProposals
            .find {
                it is BitcoinProposal && it.id == id
            } as BitcoinProposal?

    suspend fun acceptSign(id: Long){
        val prop = findBitcoinProposalInReceived(id)

        if(prop == null){
            toastMaker("Could not accept proposal.")
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
        prop.state.value = ProposalState.Started
        frostManager.acceptProposedSign(prop.id,prop.fromMid,FrostManager.SignParams.Bitcoin(prop.transaction))
    }

    suspend fun rejectSign(id: Long){
        val prop = findBitcoinProposalInReceived(id) ?: run{
            toastMaker("Could not find proposal")
            Log.d("FROST", "cold not reject sign proposal. We could not find a proposal with this id")
            return
        }
        frostManager.rejectProposedSign(id,prop.fromMid)
        prop.state.value = ProposalState.Rejected
    }

    // remove ongong actions
    suspend fun panic(){
        toastMaker("Stopping current actions")
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
