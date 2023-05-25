package nl.tudelft.trustchain.frostdao.ui.proposals

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Divider
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import nl.tudelft.trustchain.frostdao.BitcoinProposal
import nl.tudelft.trustchain.frostdao.FrostViewModel
import javax.inject.Inject


/**
 * A simple [Fragment] subclass.
 * create an instance of this fragment.
 */
@AndroidEntryPoint
class Proposals : Fragment() {
    @Inject
    lateinit var frostViewModel: FrostViewModel

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Inflate the layout for this fragment
        return ComposeView(requireContext()).apply {
            setContent {
                Column(
                    Modifier
                        .fillMaxSize()
                        .border(2.dp, Color.Gray)
                        .padding(horizontal = 8.dp)
                ) {
                    Text("My Proposals", fontSize = 32.sp)
                    Divider(thickness = 2.dp)
                    LazyColumn(
                        Modifier.fillMaxHeight(0.4f),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(frostViewModel.myProposals) {
                            Row {
                                if ((it is BitcoinProposal)) {
                                    Column {
                                        Text("status: ${it.state.value}")
                                    }
                                    Column {
                                        Text("Amount: ${it.transaction.outputSum}")
                                    }
                                }

                            }
                        }
                    }
                    Text("Proposals", fontSize = 32.sp)
                    Divider(thickness = 2.dp)
                    LazyColumn(
                        Modifier.fillMaxHeight(),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(frostViewModel.proposals) {
                            Row {
                                if (it is BitcoinProposal) {
                                    BitcoinProposalCard(
                                        proposal = it,
                                        networkParameters = frostViewModel.bitcoinService.networkParams,
                                        canAccept = true,
                                        onAccept = {
                                            frostViewModel.viewModelScope.launch(Dispatchers.Default) {
                                                frostViewModel.acceptSign(it.id)
                                            }
                                        }
                                    )

                                }

                            }
                        }
                    }
                }
            }
        }
    }

}
