package nl.tudelft.trustchain.frostdao.ui.proposals

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import nl.tudelft.trustchain.frostdao.BitcoinProposal
import nl.tudelft.trustchain.frostdao.BtcAmount
import nl.tudelft.trustchain.frostdao.recipient
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Transaction
import org.bitcoinj.params.RegTestParams

@Composable
fun BitcoinProposalCard(
    proposal: BitcoinProposal,
    networkParameters: NetworkParameters,
    canAccept: Boolean,
    onAccept: () -> Unit = {}
) {
    Card(
        Modifier
            .fillMaxWidth()
    ) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.padding(5.dp)) {
                Text("Proposed by: ${proposal.fromMid.let { "${it.take(10)}...${it.takeLast(10)}" }}")
                Text("Recipient: ${proposal.recipient(networkParameters)?.let {
                    "${it.toString().take(10)}...${it.toString().takeLast(10)}"
                } ?: "Unknown"}")
                Text("Status: ${proposal.state.value}")
                Text("Btc Amount: ${proposal.BtcAmount().toFriendlyString()}")
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                if (canAccept) {
                    Button(enabled = !proposal.started.value && !proposal.done.value,onClick = { onAccept() }) {
                        if (proposal.started.value && !proposal.done.value)
                            CircularProgressIndicator()
                        else
                            Text("Accept")

                    }
                }
            }
        }
    }
}

@Preview
@Composable
fun preview() {
    BitcoinProposalCard(
        proposal = BitcoinProposal(
            id = 0,
            fromMid = "074abd8d9c8976032cd7ca43dce36602932971ff",
            transaction = Transaction(RegTestParams()),
        ),
        networkParameters = RegTestParams.get(),
        canAccept = true,
    )
}
