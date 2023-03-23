package nl.tudelft.trustchain.frostdao

import android.Manifest
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.getSystemService
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import dagger.hilt.android.AndroidEntryPoint
import nl.tudelft.trustchain.frostdao.database.FrostDatabase
import nl.tudelft.trustchain.frostdao.ipv8.FrostCommunity
import nl.tudelft.ipv8.Overlay
import nl.tudelft.ipv8.OverlayConfiguration
import nl.tudelft.ipv8.android.IPv8Android
import nl.tudelft.ipv8.android.peerdiscovery.NetworkServiceDiscovery
import nl.tudelft.ipv8.peerdiscovery.strategy.PeriodicSimilarity
import nl.tudelft.ipv8.peerdiscovery.strategy.RandomChurn
import nl.tudelft.ipv8.peerdiscovery.strategy.RandomWalk
import nl.tudelft.trustchain.frostdao.databinding.ActivityOverviewBinding
import javax.inject.Inject

@AndroidEntryPoint
class Overview : AppCompatActivity() {

    private lateinit var binding: ActivityOverviewBinding

        @Inject
         lateinit var db: FrostDatabase
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        System.loadLibrary("rust_code")

        if(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S){
            ActivityCompat.requestPermissions(this, listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            ).toTypedArray(),1)
        }


        val frostCommunity = IPv8Android.getInstance().getOverlay<FrostCommunity>()
            ?: error("FROSTCOMMUNITY should be initialized")
        frostCommunity.initDb(db)
        binding = ActivityOverviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navView: BottomNavigationView = binding.navView

//        val navController = findNavController(R.id.nav_host_fragment_activity_overview)
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment_activity_overview) as NavHostFragment
        val navController = navHostFragment.navController
        // Passing each menu ID as a set of Ids because each
        // menu should be considered as top level destinations.
        val appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.frostSettings, R.id.propose
            )
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)
    }
}
