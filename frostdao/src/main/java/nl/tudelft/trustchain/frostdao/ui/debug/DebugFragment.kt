package nl.tudelft.trustchain.frostdao.ui.debug

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.Text
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
import nl.tudelft.trustchain.frostdao.FrostViewModel
import nl.tudelft.trustchain.frostdao.ui.settings.ActivityGrid
import javax.inject.Inject

@AndroidEntryPoint
class DebugFragment : Fragment() {
    @Inject
    lateinit var frostViewModel: FrostViewModel
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Inflate the layout for this fragment
        return ComposeView(requireContext()).apply {
            setContent {
                LazyColumn(
                    Modifier
                        .fillMaxSize()///
                        .border(2.dp, Color.Gray)
                ) {
                    item {
                        Column(Modifier.padding(horizontal = 4.dp)) {
                            Text("State: ${frostViewModel.state}", fontSize = 16.sp)
                            Text("Frost Index ${frostViewModel.index ?: "N/A"}", fontSize = 16.sp)
                            Text(
                                "Threshold: ${frostViewModel.threshold ?: "N/A"}",
                                fontSize = 16.sp
                            )
                            Text(
                                "Amount of Members: ${frostViewModel.amountOfMembers ?: "N/A"}",
                                fontSize = 16.sp
                            )
                            Text(
                                "Amount of dropped messages: ${frostViewModel.amountDropped}",
                                fontSize = 16.sp
                            )
                            Divider(thickness = 2.dp)
                            Column(Modifier.padding(horizontal = 4.dp)) {
                                Text("Bitcoin amount peers: ${frostViewModel.bitcoinNumPeers}")
                                Text("Bitcoin Address: ${frostViewModel.bitcoinAddress}")
                                Text("Bitcoin balance: ${frostViewModel.bitcoinBalance}")

                                Text("Bitcoin Dao Address: ${frostViewModel.bitcoinDaoAddress}")
                                Text("Bitcoin Dao balance: ${frostViewModel.bitcoinDaoBalance}")


                            }
                            Divider(thickness = 2.dp)
                            Text("Peers")
                            LazyColumn(Modifier.height(100.dp)) {
                                items(frostViewModel.peers) {
                                    Text(it)
                                }
                            }
                            Divider(thickness = 2.dp)

//                        Box(modifier = Modifier.fillMaxHeight())
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceAround
                            ) {
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
                                    Text(text = "Reset")
                                }

                            }
                        }
                    }

                    item {
                        ActivityGrid(frostViewModel)
                    }
                }
            }
        }
    }

}
