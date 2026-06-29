package dev.brainfence.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.brainfence.data.settings.HomeLocation
import dev.brainfence.data.settings.HomeLocationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeLocationUiState(
    val latitude: String = "",
    val longitude: String = "",
    val radiusMeters: Int = HomeLocationRepository.DEFAULT_RADIUS_M,
    val isConfigured: Boolean = false,
    val isFetchingLocation: Boolean = false,
    val error: String? = null,
    val savedMessage: String? = null,
)

@HiltViewModel
class HomeLocationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: HomeLocationRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(loadInitialState())
    val state: StateFlow<HomeLocationUiState> = _state.asStateFlow()

    private fun loadInitialState(): HomeLocationUiState {
        val home = repository.get()
        return if (home != null) {
            HomeLocationUiState(
                latitude = home.latitude.toString(),
                longitude = home.longitude.toString(),
                radiusMeters = home.radiusMeters,
                isConfigured = true,
            )
        } else {
            HomeLocationUiState()
        }
    }

    fun setLatitude(value: String) {
        _state.value = _state.value.copy(latitude = value, savedMessage = null)
    }

    fun setLongitude(value: String) {
        _state.value = _state.value.copy(longitude = value, savedMessage = null)
    }

    fun setRadius(meters: Int) {
        _state.value = _state.value.copy(
            radiusMeters = meters.coerceAtLeast(10),
            savedMessage = null,
        )
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    @SuppressLint("MissingPermission")
    fun useCurrentLocation() {
        if (!hasLocationPermission()) {
            _state.value = _state.value.copy(error = "Location permission required")
            return
        }
        _state.value = _state.value.copy(isFetchingLocation = true, error = null)
        val client = LocationServices.getFusedLocationProviderClient(context)
        client.lastLocation
            .addOnSuccessListener { location ->
                if (location != null) {
                    _state.value = _state.value.copy(
                        latitude = location.latitude.toString(),
                        longitude = location.longitude.toString(),
                        isFetchingLocation = false,
                    )
                } else {
                    _state.value = _state.value.copy(
                        isFetchingLocation = false,
                        error = "No recent location available — open Maps once to get a fix",
                    )
                }
            }
            .addOnFailureListener { e ->
                _state.value = _state.value.copy(
                    isFetchingLocation = false,
                    error = e.message ?: "Failed to read location",
                )
            }
    }

    fun save() {
        val s = _state.value
        val lat = s.latitude.toDoubleOrNull()
        val lng = s.longitude.toDoubleOrNull()
        if (lat == null || lng == null) {
            _state.value = s.copy(error = "Latitude and longitude must be valid numbers")
            return
        }
        viewModelScope.launch {
            repository.set(HomeLocation(lat, lng, s.radiusMeters))
            _state.value = _state.value.copy(
                isConfigured = true,
                error = null,
                savedMessage = "Home location saved",
            )
        }
    }

    fun clear() {
        viewModelScope.launch {
            repository.clear()
            _state.value = HomeLocationUiState(savedMessage = "Home location cleared")
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
}
