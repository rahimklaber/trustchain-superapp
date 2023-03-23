package nl.tudelft.trustchain.frostdao.ipv8

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import nl.tudelft.trustchain.frostdao.database.FrostDatabase
import nl.tudelft.ipv8.Community
import nl.tudelft.ipv8.Peer
import nl.tudelft.ipv8.messaging.Packet
import nl.tudelft.ipv8.messaging.eva.takeInRange
import nl.tudelft.ipv8.messaging.payload.IntroductionResponsePayload
import nl.tudelft.trustchain.frostdao.ipv8.message.*
import java.util.*

data class FrostMemberInfo(
    val peer : String, //use mid instead of peer. if the peer is offline, then `Peer` wont wor
    val index: Int, // index in FROST scheme
)



data class FrostGroup(
    // members is without us
    val members: List<FrostMemberInfo>,
    val myIndex: Int,
    val threshold: Int
){
    val amount : Int
        get() = members.size + 1 // we are not included here
}

//typealias OnJoinRequestCallBack = (Peer, RequestToJoinMessage) -> Unit
//typealias onJoinRequestResponseCallback = (Peer, RequestToJoinResponseMessage) -> Unit

class FrostCommunity: Community() {
    override val serviceId: String
        get() = "5ce0aab9123b60537030b1312783a0ebcf5fd92f"

    private val _channel = MutableSharedFlow<Pair<Peer, FrostMessage>>(extraBufferCapacity = 10000) //todo check this
    private val _filteredChannel = MutableSharedFlow<Pair<Peer, FrostMessage>>(extraBufferCapacity = 10000)
    val channel = _filteredChannel.asSharedFlow()

    val lastResponseFrom = mutableMapOf<String,Date>()

    override fun onIntroductionResponse(peer: Peer, payload: IntroductionResponsePayload) {
        super.onIntroductionResponse(peer, payload)
        lastResponseFrom[peer.mid] = Date()
    }

    lateinit var  db: FrostDatabase
    fun initDb(db: FrostDatabase){
        this.db = db
    }

    private var onAckCbId = 0;
    private val onAckCallbacks= mutableMapOf<Int,suspend (peer: Peer,ack: Ack)->Unit>()
    //todo, check if we need to lock
    fun addOnAck(cb: suspend (peer: Peer,ack: Ack)->Unit) : Int{
       val id = onAckCbId++;
       onAckCallbacks[id]  = cb
        return id
    }

    fun removeOnAck(id: Int){
        onAckCallbacks.remove(id)
    }

    init {

        //todo maybe deserialize
        messageHandlers[RequestToJoinMessage.MESSAGE_ID] = { packet ->
            val pair = packet.getAuthPayload(RequestToJoinMessage.Deserializer)
//            error("received ${pair.second}")
            //todo check this
            scope.launch {
                _channel.emit(pair)
            }
        }
        messageHandlers[RequestToJoinResponseMessage.MESSAGE_ID] = { packet ->
            val pair = packet.getAuthPayload(RequestToJoinResponseMessage.Deserializer)
            //todo check this
            scope.launch {
                _channel.emit(pair)
            }
        }
        messageHandlers[KeyGenCommitments.MESSAGE_ID] = { packet ->
            val pair = packet.getAuthPayload(KeyGenCommitments.Deserializer)
            //todo check this
            scope.launch {
                _channel.emit(pair)
            }
        }
        messageHandlers[KeyGenShare.MESSAGE_ID] = { packet ->
            val pair = packet.getAuthPayload(KeyGenShare.Deserializer)
            //todo check this
            scope.launch {
                _channel.emit(pair)
            }
        }
        messageHandlers[Preprocess.MESSAGE_ID] = { packet ->
            val pair = packet.getAuthPayload(Preprocess.Deserializer)
            //todo check this
            scope.launch {
                _channel.emit(pair)
            }
        }
        messageHandlers[SignShare.MESSAGE_ID] = { packet ->
            val pair = packet.getAuthPayload(SignShare.Deserializer)
            //todo check this
            scope.launch {
                _channel.emit(pair)
            }
        }
        messageHandlers[SignRequest.MESSAGE_ID] = { packet ->
            val pair = packet.getAuthPayload(SignRequest.Deserializer)
            //todo check this
            scope.launch {
                _channel.emit(pair)
            }
        }
        messageHandlers[SignRequestResponse.MESSAGE_ID] = { packet ->
            val pair = packet.getAuthPayload(SignRequestResponse.Deserializer)
            //todo check this
            scope.launch(Dispatchers.Default) {
                _channel.emit(pair)
            }

        }
        messageHandlers[GossipRequest.MESSAGE_ID] = { packet ->
            val (peer,msg) = packet.getAuthPayload(GossipRequest.Deserializer)
            //todo check this
            scope.launch(Dispatchers.Default) {
                db.requestDao()
                    .getNotDoneAndReceivedAfterTime(msg.afterUnixTime.toInt())
                    .forEach {
                        launch {
                            delay(20)

                            // deserialize the msg to be able to create the packet with `serializePacket`
                            val deserializedMsg = deserializerFromId(it.type).deserialize(it.data).first

                            val response = GossipResponse(it.fromMid,deserializedMsg)
                            Log.d("FROST", "responding to gossip request with response: $response")

                            val packet = serializePacket(GossipResponse.MESSAGE_ID,response)

                            send(peer, packet)
                        }
                    }
            }

        }

        messageHandlers[GossipResponse.MESSAGE_ID] = { packet ->
            Log.d("FROST", "Received gossip response")
            val (peer,msg) = packet.getAuthPayload(GossipResponse.Deserializer)
            getPeers().find{
                it.mid == msg.originallyFromMid
            }// so if we know the peer, then we should do something. Otherwise it probably doesn't matter
                ?.also {originalPeer ->
                    scope.launch(Dispatchers.Default) {
                        _channel.emit(originalPeer to msg.payload)
                    }
                }
        }

        messageHandlers[Ack.MESSAGE_ID] = { packet ->
            Log.d("FROST", "Received Ack")
            val (peer,msg) = packet.getAuthPayload(Ack.Deserializer)
            onAckCallbacks.forEach { (i, callback) -> scope.launch(Dispatchers.Default){  callback(peer,msg) }}
        }
        evaProtocolEnabled = true
    }

    override fun load() {
        super.load()
        setOnEVAReceiveCompleteCallback { peer, info, id, data ->
            if (data == null){
                Log.d("FROST","received eva data, but is null")
            }
            Log.d("FROST", "RECEIVED data via EVA type msg = ${data!![0].toInt()}")
            if (info != EVA_FROST_DAO_attachment)
                return@setOnEVAReceiveCompleteCallback
            data.let {
                val packet = Packet(peer.address, data.takeInRange(1, data.size))

                messageHandlers[data[0].toInt()]?.let { it1 -> it1(packet) }
            }
        }
        scope.launch(Dispatchers.Default) {
            _channel.collect {(peer, msg) ->
                val contains = received.containsKey(peer.mid to msg.hashCode())
                if (!contains){
                    Log.d("FROST", "Does not contain $msg")
                    received[peer.mid to msg.hashCode()]=true
                    _filteredChannel.emit(peer to msg)
                }
            }
        }
        scope.launch(Dispatchers.Default){
            _channel.collect{(peer,msg)->
                    Log.d("FROST","sending ack for $msg")
                    sendAck(peer, Ack(msg.hashCode()))
            }
        }
        scope.launch(Dispatchers.Default) {
            var afterDate = Date().time / 1000
            while (true) {
                delay(delayAmount + 600_000)
                Log.d("FROST", "sending gossip request")
                //after 2 min ( so everything loads), as send gossiprequest
                val request = GossipRequest(afterDate)
                val packet = serializePacket(GossipRequest.MESSAGE_ID, request)
                for (peer in getPeers()) {
                    scope.launch(Dispatchers.Default) {
                        send(peer, packet)
                    }
                    sent[peer.mid to request.hashCode()] = true
              }
          }
          }
    }


    // store that we received a msg.
    // (mid,hash) -> Boolean
    val sent = mutableMapOf<Pair<String,Int>,Boolean>()
    val received  = mutableMapOf<Pair<String,Int>,Boolean>()
    // better name lol
    fun sendForPublic(peer: Peer, msg: FrostMessage) {
//        Log.d("FROST", "sending msg $msg in community")
        val id = messageIdFromMsg(msg)
        val packet = serializePacket(id,msg)
//        scope.launch(Dispatchers.Default) {
//            repeat(10){
//
//            }
//        }
        send(peer,packet)
        sent[peer.mid to msg.hashCode()] = true

    }

    fun sendProposalStatusRequest(peer: Peer ,request: ProposalStatusRequest){
        val packet = serializePacket(ProposalStatusRequest.MESSAGE_ID,request)
        send(peer,packet)
    }

    fun sendAck(peer: Peer,ack: Ack){
        val packet = serializePacket(Ack.MESSAGE_ID,ack)
        send(peer,packet)
    }


    fun broadcast(msg : FrostMessage){
        //todo fix this

        val packet = serializePacket(messageIdFromMsg(msg),msg)
        for (peer in getPeers()) {
           scope.launch(Dispatchers.Default) {
                   send(peer,packet)
           }
            sent[peer.mid to msg.hashCode()] = true
        }
    }



    companion object {
        const val EVA_FROST_DAO_attachment = "eva_frost_attachment"
        const val delayAmount = /*5 * 60 * 1000L*/ 1000L

    }


}
