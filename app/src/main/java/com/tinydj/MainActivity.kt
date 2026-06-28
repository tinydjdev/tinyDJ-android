package com.tinydj

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tinydj.ui.deck.MainTabScreen
import com.tinydj.ui.deck.DeckViewModel
import com.tinydj.ui.theme.TinyDjTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 101)
        }
        val container = (application as TinyDjApp).container
        setContent {
            TinyDjTheme {
                val vm: DeckViewModel = viewModel(factory = DeckViewModel.factory(container))
                MainTabScreen(vm)
            }
        }
    }
}
