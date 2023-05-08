package nl.tudelft.trustchain.frostdao.bitcoin

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import nl.tudelft.ipv8.util.hexToBytes
import org.bitcoinj.core.*
import org.bitcoinj.kits.WalletAppKit
import org.bitcoinj.params.RegTestParams
import org.bitcoinj.wallet.KeyChain
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class BitcoinService(
    walletDir: File,
    peerAddress: String = "6.tcp.ngrok.io",
    val networkParams: NetworkParameters = RegTestParams.get(),
    walletName: String = "FrostDaoWallet4",
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    lateinit var kit: WalletAppKit
    private val trackedAddresses = mutableListOf<Address>()

    lateinit var personalAddress: Address
        private set

    init {
        kit = object : WalletAppKit(networkParams, walletDir, walletName) {
            override fun onSetupCompleted() {
                if (wallet().keyChainGroupSize == 0) {
                    wallet().importKey(ECKey())
                }
                personalAddress = wallet().currentAddress(KeyChain.KeyPurpose.RECEIVE_FUNDS)
            }
        }
        scope.launch {

            kit.setPeerNodes(PeerAddress(networkParams, InetAddress.getByName(peerAddress), 11296/*networkParams.port*/))
            kit.startAsync()
                kit.awaitRunning(60, TimeUnit.SECONDS)
        }
    }

    /**
     * Creates an [Address] from a schnorr pubkey corresponding to a taproot keyspend address.
     * @param pubKeyHex: The (offset) public key in hex format.
     */
    fun taprootPubKeyToAddress(pubKeyHex: String): Address {
        return SegwitAddress.fromProgram(networkParams, 1, pubKeyHex.hexToBytes())
    }

    /**
     * Use this to keep track of the "DAO" address
     * @param pubKeyHex: public key of the DAO account in hex format. In more technical terms,
     * this is a taproot keyspend address.
     */
    fun trackPubKey(pubKeyHex: String) {
        val segwitAddress = taprootPubKeyToAddress(pubKeyHex)
        trackAddress(segwitAddress)
    }

    /**
     * Use this to keep track of the "DAO" address
     * @param address: adress of the "DAO" account.
     */
    fun trackAddress(address: Address) {
        kit.wallet().addWatchedAddress(address)
        trackedAddresses.add(address)
    }

    /**
     * Get outputs from addresses that we are tracking.
     */
    fun getTrackedOutputs(): List<TransactionOutput> {
        return kit.wallet().getWatchedOutputs(true)
    }

    fun getTrackedOutputsForAddress(address: Address): List<TransactionOutput> {
        return getTrackedOutputs().filter { it.scriptPubKey.getToAddress(networkParams) == address }
    }

    fun getBalanceForTrackedAddress(address: Address): Coin {
        return getTrackedOutputsForAddress(address)
            .sumOf { it.value.value }.let {
                Coin.valueOf(it)
            }
    }
    fun sendBtcToTaproot(taprootAddress: SegwitAddress, amount: Coin) {
        kit.wallet().sendCoins({
            Log.d("FROST", "Bitcoin tx hash: ${it.txId}")
            kit.peerGroup().broadcastTransaction(it)
        }, taprootAddress, amount)
    }

    fun createSendTransactionForDaoAccount(daoAddress: Address, amount: Coin, destination: Address) : Result<Transaction>{
        val potentialOutputs = getTrackedOutputsForAddress(daoAddress).filter {
            it.value > amount
        }
        if(potentialOutputs.isEmpty())
            return Result.failure(Exception("No outputs available for sending amount: $amount"))

        val transaction = Transaction(networkParams)

        transaction.addInput(potentialOutputs.first())

        transaction.addOutput(amount, destination)

        return Result.success(transaction)
    }


}
