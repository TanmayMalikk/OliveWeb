package com.olive.app.ui.covers.olive

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.olive.app.data.DemoCatalogRepository
import java.util.Locale

// Base resources preserve legacy ingredient identities in saved carts; FoodLabels renders the active locale.
@Composable internal fun oliveViewModel():OliveViewModel {
    val application=LocalContext.current.applicationContext
    val factory=remember(application) {
        val base=Configuration(application.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        val resources=application.createConfigurationContext(base).resources
        viewModelFactory { initializer { OliveViewModel(DemoCatalogRepository(resources::getString)) } }
    }
    return viewModel(factory=factory)
}
