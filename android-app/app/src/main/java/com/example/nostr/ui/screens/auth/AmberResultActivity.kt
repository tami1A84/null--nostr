package com.example.nostr.ui.screens.auth

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import timber.log.Timber

/**
 * Activity to receive callback results from Amber signer app (NIP-55)
 */
class AmberResultActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Handle the callback from Amber
        handleAmberCallback(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAmberCallback(intent)
        finish()
    }

    private fun handleAmberCallback(intent: Intent?) {
        if (intent == null) {
            Timber.w("Amber callback with null intent")
            return
        }

        val data = intent.data
        if (data == null) {
            Timber.w("Amber callback with null data")
            return
        }

        Timber.d("Amber callback received: $data")

        // Parse the callback
        when (data.host) {
            "amber-callback" -> {
                val signature = data.getQueryParameter("signature")
                val event = data.getQueryParameter("event")
                val id = data.getQueryParameter("id")

                if (signature != null) {
                    Timber.d("Received signature from Amber: $signature")
                    // TODO: Complete the pending signing request
                    AmberIntegration.completePendingRequest(id, signature, event)
                }
            }
        }
    }
}
