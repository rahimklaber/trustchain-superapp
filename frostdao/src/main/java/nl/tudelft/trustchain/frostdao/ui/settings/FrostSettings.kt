package nl.tudelft.trustchain.frostdao.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import nl.tudelft.trustchain.frostdao.FrostViewModel
import javax.inject.Inject

/**
 * A simple [Fragment] subclass.
 * Use the [FrostSettings.newInstance] factory method to
 * create an instance of this fragment.
 */
@AndroidEntryPoint
class FrostSettings : Fragment() {
    @Inject
    lateinit var frostViewModel: FrostViewModel

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Inflate the layout for this fragment
        return ComposeView(requireContext()).apply {
            setContent {
                val clipboard = LocalClipboardManager.current
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Card(
                        Modifier
                            .fillMaxWidth(0.8f)
                            .padding(10.dp)
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(5.dp), horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(frostViewModel.bitcoinDaoBalance ?: "??", fontSize = 40.sp)
                            Text("DAO Balance", color = Color.Gray)
                        }
                    }
                    Card(
                        Modifier
                            .fillMaxWidth(0.8f)
                            .padding(10.dp)
                    ) {
                        Text(
                            "DAO Details",
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            fontSize = 20.sp
                        )
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(5.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceAround
                            ) {
                                Text("DAO account: ${frostViewModel.bitcoinDaoAddress?.let {
                                    "${it.toString().take(5)}...${it.toString().takeLast(5)}"
                                } ?: "N/A"}")
                                Button(
                                    enabled = frostViewModel.bitcoinDaoAddress != null,
                                    onClick = {
                                        if (frostViewModel.bitcoinDaoAddress != null) {
                                            clipboard.setText(AnnotatedString(frostViewModel.bitcoinDaoAddress.toString()))
                                            frostViewModel.toastMaker("Copied address.")
                                        }
                                    }) {
                                    Text("Copy")
                                }
                            }
                            Text("DAO ID: xxxx")
                            Text("${frostViewModel.amountOfMembers ?: "?"} Members")
                            Button(onClick = {
                                frostViewModel.viewModelScope.launch(Dispatchers.Default) {
                                    frostViewModel.joinFrost()
                                }
                            }) {
                                Text(text = "Join Group")
                            }

                        }
                    }

                    Card(
                        Modifier
                            .fillMaxWidth(0.8f)
                            .padding(10.dp)
                    ) {
                        ActivityGrid(viewModel = frostViewModel)
                    }
                }
            }
        }
    }

}

/*setContent {
                var senAddress by remember{ mutableStateOf("") }
                Box(
                    Modifier
                        .fillMaxSize()///
                        .border(2.dp, Color.Gray)){
                    Column(Modifier.padding(horizontal = 4.dp)) {
                        Text("State: ${frostViewModel.state}", fontSize = 16.sp)
                        Text("Frost Index ${frostViewModel.index ?: "N/A"}", fontSize = 16.sp)
                        Text("Threshold: ${frostViewModel.threshold ?: "N/A"}", fontSize = 16.sp)
                        Text("Amount of Members: ${frostViewModel.amountOfMembers ?: "N/A"}", fontSize = 16.sp)
                        Text("Amount of dropped messages: ${frostViewModel.amountDropped}",fontSize = 16.sp)
                        Divider(thickness = 2.dp)
                        Text("Peers")
                        Column(Modifier.padding(horizontal = 4.dp)){
                            Text("Bitcoin amount peers: ${frostViewModel.bitcoinNumPeers}")
                            Text("Bitcoin Address: ${frostViewModel.bitcoinAddress}")
                            Text("Bitcoin balance: ${frostViewModel.bitcoinBalance}")

                            Text("Bitcoin Dao Address: ${frostViewModel.bitcoinDaoAddress}")
                            Text("Bitcoin Dao balance: ${frostViewModel.bitcoinDaoBalance}")


                            TextField(senAddress,{senAddress = it})
                            Button({
                                frostViewModel.viewModelScope.launch(Dispatchers.Default) {
                                    frostViewModel.bitcoinService.sendBtcToTaproot(
                                        SegwitAddress.fromBech32(RegTestParams.get(),senAddress),
                                        Coin.CENT
                                    )
                                }
                            }){
                                Text("Test send")
                            }


                        }
                        LazyColumn{
                            items(frostViewModel.peers){
                                Text(it)
                            }
                        }
//                        Box(modifier = Modifier.fillMaxHeight())
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ){
                            Button(onClick = {
                                frostViewModel.viewModelScope.launch(Dispatchers.Default) {
                                    frostViewModel.joinFrost()
                                }
                            }) {
                                Text(text = "Join Group")
                            }

                            Button(onClick = {
                                frostViewModel.viewModelScope.launch(Dispatchers.Default) {
                                    frostViewModel.panic()
                                }
                            }) {
                                Text(text = "Panic")
                            }

                        }


                        ActivityGrid(frostViewModel)
                    }
                }
            }*/
