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
import nl.tudelft.trustchain.frostdao.ipv8.FrostCommunity
import nl.tudelft.trustchain.frostdao.ipv8.FrostManager
import nl.tudelft.trustchain.frostdao.ipv8.FrostState
import nl.tudelft.trustchain.frostdao.ipv8.Update
import nl.tudelft.trustchain.frostdao.ipv8.message.RequestToJoinMessage
import nl.tudelft.trustchain.frostdao.ipv8.message.SignRequest
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
    val signed: Boolean = false,
    val signatureHex: String = "",
    override var state: ProposalState = ProposalState.Started
) : Proposal {
    override fun type(): String = "Sign"
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
    val toastMaker : (String) -> Unit
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
    init {
        viewModelScope.launch(Dispatchers.Default) {

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
                            else -> Unit
                        }
                    }
            }
            frostManager.updatesChannel.collect{
                Log.d("FROST","received msg in viewmodel : $it")
                when(it){
                    is Update.KeyGenDone, is Update.StartedKeyGen, is Update.ProposedKeyGen-> {
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
                    is Update.SignDone -> {
                        refreshFrostData()
                        val update = it
                        val foundInMyProps = myProposals.find { prop ->
                            if(prop is SignProposal){
                                prop.id == update.id
                            }else{
                                false
                            }
                        } as SignProposal?
                        if (foundInMyProps != null){
                            myProposals.removeIf { prop ->
                                if(prop is SignProposal){
                                prop.id == update.id
                            }else{
                                false
                                }
                            }
                            myProposals.add(
                                foundInMyProps.copy(
                                    signed = true,
                                    signatureHex = update.signature
                                )
                            )
                            return@collect
                        }

                        val foundInProps = proposals.find { prop ->
                            if(prop is SignProposal){
                                prop.id == update.id
                            }else{
                                false
                            }
                        } as SignProposal?

                        if (foundInProps != null){
                            proposals.removeIf { prop ->
                                if(prop is SignProposal){
                                    prop.id == update.id
                                }else{
                                    false
                                }
                            }
                            proposals.add(
                                foundInProps.copy(
                                    signed = true,
                                    signatureHex = update.signature
                                )
                            )
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
    fun joinFrost(){
        viewModelScope.launch(Dispatchers.Default){
            frostManager.joinGroup()
        }
    }

    suspend fun proposeSign(data: ByteArray){
        val (ok, id) = frostManager.proposeSignAsync(data)
        if (!ok){
            Log.d("FROST", "Failed to create sign proposal")
            return
        }
        myProposals.add(SignProposal(id, frostCommunity.myPeer.mid, data))

    }

    suspend fun acceptSign(id: Long){
        val prop = proposals
            .find {
                it is SignProposal && it.id == id
            } as SignProposal?

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

        frostManager.acceptProposedSign(prop.id,prop.fromMid,prop.msg)
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
