package com.reelwell.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {
    var input by mutableStateOf("")
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var result by mutableStateOf<ResolvedMedia?>(null)
        private set

    fun resolve() {
        if (loading) return
        error = null
        result = null
        loading = true
        viewModelScope.launch {
            try {
                result = Resolver.resolve(input)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ResolveException) {
                error = e.message
            } catch (e: Exception) {
                error = "Could not resolve that link."
            } finally {
                loading = false
            }
        }
    }

    fun receiveShared(text: String) {
        input = text
        resolve()
    }

    fun clear() {
        input = ""
        error = null
        result = null
    }
}
