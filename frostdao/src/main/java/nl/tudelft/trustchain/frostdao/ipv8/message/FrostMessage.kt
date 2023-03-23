package nl.tudelft.trustchain.frostdao.ipv8.message

import nl.tudelft.ipv8.messaging.Deserializable
import nl.tudelft.ipv8.messaging.Serializable

sealed interface FrostMessage: Serializable {
    val id: Long
}
fun messageIdFromMsg(msg: FrostMessage) : Int =
    when(msg){
        is KeyGenCommitments -> KeyGenCommitments.MESSAGE_ID
        is KeyGenShare -> KeyGenShare.MESSAGE_ID
        is RequestToJoinMessage -> RequestToJoinMessage.MESSAGE_ID
        is RequestToJoinResponseMessage -> RequestToJoinResponseMessage.MESSAGE_ID
        is Preprocess -> Preprocess.MESSAGE_ID
        is SignShare -> SignShare.MESSAGE_ID
        is SignRequest -> SignRequest.MESSAGE_ID
        is SignRequestResponse -> SignRequestResponse.MESSAGE_ID
        else -> error("TODO: Compiler has a bug??")
    }

fun deserializerFromId(id: Int) : Deserializable<out FrostMessage> =
    when(id){
         KeyGenCommitments.MESSAGE_ID -> KeyGenCommitments
         KeyGenShare.MESSAGE_ID -> KeyGenShare
         RequestToJoinMessage.MESSAGE_ID -> RequestToJoinMessage
         RequestToJoinResponseMessage.MESSAGE_ID -> RequestToJoinResponseMessage
         Preprocess.MESSAGE_ID -> Preprocess
         SignShare.MESSAGE_ID -> SignShare
         SignRequest.MESSAGE_ID -> SignRequest
         SignRequestResponse.MESSAGE_ID -> SignRequestResponse
        else -> error("could not find deserializer for id : $id")
    }
