package nl.tudelft.trustchain.frostdao.hilt

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.tudelft.trustchain.frostdao.FrostViewModel
import nl.tudelft.trustchain.frostdao.database.FrostDatabase
import nl.tudelft.trustchain.frostdao.database.Request
import nl.tudelft.trustchain.frostdao.database.SentMessage
import nl.tudelft.trustchain.frostdao.ipv8.FrostCommunity
import nl.tudelft.trustchain.frostdao.ipv8.FrostManager
import nl.tudelft.trustchain.frostdao.ipv8.NetworkManager
import nl.tudelft.trustchain.frostdao.ipv8.message.FrostMessage
import nl.tudelft.trustchain.frostdao.ipv8.message.SignRequest
import nl.tudelft.trustchain.frostdao.ipv8.message.messageIdFromMsg
import nl.tudelft.ipv8.Peer
import nl.tudelft.ipv8.android.IPv8Android
import nl.tudelft.trustchain.frostdao.bitcoin.BitcoinService
import java.util.Date
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object FrostManagerModule {
    @Provides
    @Singleton
    fun provideFrostViewModel(db: FrostDatabase, @ApplicationContext app: Context) : FrostViewModel {
        val frostCommunity = IPv8Android.getInstance().getOverlay<FrostCommunity>()
            ?: error("FROSTCOMMUNITY should be initialized")
        val frostManager = FrostManager(
            frostCommunity.channel,
            db = db,
            networkManager = object : NetworkManager() {
                override fun peers(): List<Peer> = frostCommunity.getPeers()
                override suspend fun send(peer: Peer, msg: FrostMessage): Pair<Boolean, Int> {
                    Log.d("FROST", "sending: $msg")
                    db.sentMessageDao().insertSentMessage(
                        SentMessage(
                            toMid = peer.mid,
                            data = msg.serialize(),
                            broadcast = false,
                            messageId = msg.id,
                            type = messageIdFromMsg(msg),
                            unixTime = Date().time / 1000 // in seconds
                        )
                    )
                    if (messageIdFromMsg(msg) == SignRequest.MESSAGE_ID) {
                        db.requestDao()
                            .insertRequest(
                                Request(
                                    unixTime = Date().time / 1000,
                                    type = messageIdFromMsg(msg),
                                    requestId = msg.id,
                                    data = msg.serialize(),
                                    fromMid = frostCommunity.myPeer.mid,
                                )
                            )
                    }
                    val done = CompletableDeferred<Unit>(null)
                    val cbId = frostCommunity.addOnAck { peer, ack ->
                        if (ack.hashCode == msg.hashCode()){
                            Log.d("FROST","received ack for $msg")
                            done.complete(Unit)
                        }
                    }

                    frostCommunity.send(peer, msg)
                    var amountDropped = 0
                    for (i in 0..5) {
                        val x = select {
                            onTimeout(1000) {
                                amountDropped +=1
                                Log.d("FROST","resending $msg $i th time")
                                frostCommunity.send(peer, msg)
                                false
                            }
                            done.onAwait {
                                true
                            }
                        }
                        if (x)
                            break

                    }

                    if (!done.isCompleted)
                    {
                        // wait for 1 sec to see if a msg arrives
                        // deals with the case where the msgs timed out, but we resend it in the last iteration of the loop
                        delay(1000)
                    }

                    frostCommunity.removeOnAck(cbId)

                    return done.isCompleted to amountDropped

                }

                override suspend fun broadcast(msg: FrostMessage, recipients: List<Peer>): Pair<Boolean, Int> {
                    val recipients = recipients.ifEmpty {
                        frostCommunity.getPeers()
                    }
                    Log.d("FROST", "broadcasting: $msg")
                    db.sentMessageDao().insertSentMessage(
                        SentMessage(
                            toMid = null,
                            data = msg.serialize(),
                            broadcast = true,
                            messageId = msg.id,
                            type = messageIdFromMsg(msg),
                            unixTime = Date().time / 1000 // in seconds
                        )
                    )
                    if (messageIdFromMsg(msg) == SignRequest.MESSAGE_ID) {
                        db.requestDao()
                            .insertRequest(
                                Request(
                                    unixTime = Date().time / 1000,
                                    type = messageIdFromMsg(msg),
                                    requestId = msg.id,
                                    data = msg.serialize(),
                                    fromMid = frostCommunity.myPeer.mid,
                                )
                            )
                    }
                    var amountDropped = 0
                    val droppedMutex = Mutex()
                    val incDropped  = suspend {
                        droppedMutex.withLock {
                            amountDropped+=1
                        }
                    }

                    val workScope = CoroutineScope(Dispatchers.Default)
                    val deferreds = recipients.map { peer ->
                        workScope.async {
                            val done = CompletableDeferred<Unit>(null)
                            val cbId = frostCommunity.addOnAck { ackSource, ack ->
                                //todo, need to also check the peer when broadcasting
                                if (ack.hashCode == msg.hashCode() && peer.mid == ackSource.mid){
                                    Log.d("FROST","received ack for $msg")

                                    done.complete(Unit)
                                }
                            }

                            frostCommunity.send(peer, msg)

                            for (i in 0..5) {
                                val x = select {
                                    onTimeout(1000) {
                                        //todo what if this is the last iteration
                                        frostCommunity.send(peer, msg)
                                        incDropped()
                                        false
                                    }
                                    done.onAwait {
                                        true
                                    }
                                }
                                if (x)
                                    break

                            }
                            if (!done.isCompleted)
                            {
                                // wait for 1 sec to see if a msg arrives
                                // deals with the case where the msgs timed out, but we resend it in the last iteration of the loop
                                delay(1000)
                            }
                            frostCommunity.removeOnAck(cbId)
                            done.isCompleted
                        }
                    }

                    for (deferred in deferreds) {
                        // failed
                        if(!deferred.await()){
                            workScope.cancel()
                            return false to amountDropped
                        }
                    }
                    //success
                    return true to amountDropped

                }

                override fun getMyPeer(): Peer = frostCommunity.myPeer

                override fun getPeerFromMid(mid: String): Peer =
                    frostCommunity.getPeers().find { it.mid == mid } ?: error("Could not find peer")

            },
        )
        return FrostViewModel(frostCommunity,frostManager, BitcoinService(app.dataDir)){
            GlobalScope.launch(Dispatchers.Main) {
                Toast.makeText(app,it,Toast.LENGTH_LONG).show()
            }
        }
    }
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext app: Context
    )=
        Room.databaseBuilder(
            app,
            FrostDatabase::class.java,
            "frost_db"
        ).build()


}
