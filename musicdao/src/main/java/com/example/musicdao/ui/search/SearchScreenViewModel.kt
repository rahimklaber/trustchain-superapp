package com.example.musicdao.ui.search

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.musicdao.AppContainer
import com.example.musicdao.domain.usecases.SearchUseCase
import com.example.musicdao.model.Album
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SearchScreenViewModel(
    private val searchUseCase: SearchUseCase
) : ViewModel() {

    private val DEBOUNCE_DELAY = 200L

    private val _searchQuery: MutableStateFlow<String> = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _searchResult: MutableStateFlow<List<Album>> = MutableStateFlow(listOf())
    val searchResult: StateFlow<List<Album>> = _searchResult

    private var searchJob: Job? = null

    @RequiresApi(Build.VERSION_CODES.O)
    fun searchDebounced(searchText: String) {
        _searchQuery.value = searchText

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_DELAY)
            search(searchText)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun search(searchText: String) {
        if (searchText.isEmpty()) {
            _searchResult.value = listOf()
        } else {
            val result = searchUseCase.invoke(searchText)
            _searchResult.value = result
        }
    }

    companion object {
        fun provideFactory(
            searchUseCase: SearchUseCase = AppContainer.searchUseCase
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SearchScreenViewModel(searchUseCase) as T
            }
        }
    }
}
