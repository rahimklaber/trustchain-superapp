package nl.tudelft.trustchain.frostdao.ui.make_proposal

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import javax.inject.Inject
import kotlin.random.Random


/**
 * A simple [Fragment] subclass.
 * create an instance of this fragment.
 */
@AndroidEntryPoint
class Propose : Fragment() {
    @Inject
    lateinit var frostViewModel: FrostViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                var address by remember{ mutableStateOf("")}
                var sendAmount by remember{ mutableStateOf(0L)}
                Box(
                    Modifier
                        .fillMaxSize()
                        .border(2.dp, Color.Gray)){
                    Column(Modifier.padding(horizontal = 4.dp).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Propose Signing", fontSize = 24.sp)

                        TextField(placeholder = { Text("Destination address")},value = address, onValueChange = {address = it})
                        TextField(placeholder = { Text("Amount in satoshi")},value = sendAmount.toString(), onValueChange = {runCatching { sendAmount = it.toLong() }})

                        Button(onClick = {
                            frostViewModel.viewModelScope.launch (Dispatchers.Default){
//                                frostViewModel.proposeSign(Random.nextBytes(32))
                                frostViewModel.proposeSignBitcoin(sendAmount,address)
                            }
                        }) {
                            Text("Propose")
                        }
                    }
                }
            }
        }
    }
}
