package nl.tudelft.trustchain.frostdao.ipv8.message

import android.util.Log
import nl.tudelft.ipv8.messaging.Deserializable
import nl.tudelft.ipv8.util.hexToBytes
import nl.tudelft.ipv8.util.toHex

data class Preprocess(override val id:Long, val bytes: ByteArray, val participants: List<Int> = listOf()) :
    FrostMessage {

    override fun serialize(): ByteArray {
        return "$id#${bytes.toHex()}#${participants.joinToString(",")}".toByteArray(Charsets.UTF_8)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Preprocess

        if (id != other.id) return false
        if (!bytes.contentEquals(other.bytes)) return false
        if (participants != other.participants) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + participants.hashCode()
        return result
    }


    companion object Deserializer : Deserializable<Preprocess>{
        const val MESSAGE_ID = 4;
        override fun deserialize(buffer: ByteArray, offset: Int): Pair<Preprocess, Int> {
            val arr = buffer.slice(offset until buffer.size).toByteArray().toString(Charsets.UTF_8)
            val (idstr,preprocess_hex, list_str) = arr.split("#")

            Log.d("FROST", list_str)

            return Preprocess(
                idstr.toLong(),
                preprocess_hex.hexToBytes(),
                if (list_str.isBlank()) listOf() else list_str.split(",").map(String::toInt)
            ) to buffer.size

        }

    }
}
data class SignShare(override val id: Long, val bytes: ByteArray) : FrostMessage {


    override fun serialize(): ByteArray {
        return "$id#${bytes.toHex()}".toByteArray(Charsets.UTF_8)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SignShare

        if (id != other.id) return false
        if (!bytes.contentEquals(other.bytes)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }

    companion object Deserializer : Deserializable<SignShare>{
        const val MESSAGE_ID = 5;
        override fun deserialize(buffer: ByteArray, offset: Int): Pair<SignShare, Int> {
            val (idstr, byteshex) = buffer.slice(offset until buffer.size).toByteArray().toString(Charsets.UTF_8).split("#")
            return SignShare(idstr.toLong(),byteshex.hexToBytes()) to buffer.size
        }

    }
}

/**
 * todo: Ok so I need the tx hash, the index of the output I want to use as input, the recipient of the send and the amount we are sending
 */

data class SignRequestBitcoin(override val id: Long, val tx: ByteArray): FrostMessage {
    override fun serialize(): ByteArray {
        return "$id#${tx.toHex()}".encodeToByteArray()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SignRequestBitcoin

        if (id != other.id) return false
        return tx.contentEquals(other.tx)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + tx.contentHashCode()
        return result
    }

    companion object Deserializer : Deserializable<SignRequestBitcoin> {
        const val MESSAGE_ID = 15
        override fun deserialize(buffer: ByteArray, offset: Int): Pair<SignRequestBitcoin, Int> {
            val (idstr, txHex) = buffer.slice(offset until  buffer.size)
                .toByteArray()
                .toString(Charsets.UTF_8)
                .split("#")
            return SignRequestBitcoin(
                idstr.toLong(),
                txHex.hexToBytes()
            ) to buffer.size
        }
    }

}

data class SignRequest(override val id: Long, val data: ByteArray) : FrostMessage {
    override fun serialize(): ByteArray {
        return "$id#${data.toHex()}".toByteArray(Charsets.UTF_8)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SignRequest

        if (id != other.id) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }

    companion object Deserializer : Deserializable<SignRequest>{
        const val MESSAGE_ID = 6
        override fun deserialize(buffer: ByteArray, offset: Int): Pair<SignRequest, Int> {
            val (idstr, byteshex) = buffer.slice(offset until  buffer.size)
                .toByteArray()
                .toString(Charsets.UTF_8)
                .split("#")
            return SignRequest(
                idstr.toLong(),
                byteshex.hexToBytes()
            ) to buffer.size
        }
    }

}

data class SignRequestResponse(override val id: Long, val ok: Boolean) : FrostMessage {
    override fun serialize(): ByteArray {
        return "$id#$ok".toByteArray(Charsets.UTF_8)
    }

    companion object Deserializer: Deserializable<SignRequestResponse>{
        const val MESSAGE_ID = 7
        override fun deserialize(buffer: ByteArray, offset: Int): Pair<SignRequestResponse, Int> {
            val (idstr, okstr) = buffer.slice(offset until buffer.size)
                .toByteArray()
                .toString(Charsets.UTF_8)
                .split("#")
            return SignRequestResponse(idstr.toLong(),okstr.toBooleanStrict()) to buffer.size

        }

    }
}
