package nl.tudelft.trustchain.frostdao.ui.settings

import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import nl.tudelft.trustchain.frostdao.FrostViewModel
import nl.tudelft.trustchain.frostdao.ui.settings.ActivityGrid
import org.bitcoinj.core.Coin
import org.bitcoinj.core.SegwitAddress
import org.bitcoinj.params.RegTestParams
import javax.inject.Inject

/**
 * A simple [Fragment] subclass.
 * Use the [FrostSettings.newInstance] factory method to
 * create an instance of this fragment.
 */
@AndroidEntryPoint
class FrostSettings : Fragment() {
    @Inject lateinit var frostViewModel : FrostViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View{
        // Inflate the layout for this fragment
        return ComposeView(requireContext()).apply {
            setContent {
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
            }
        }
    }

}
