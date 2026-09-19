package com.kingzcheung.xime.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kingzcheung.xime.settings.DictEntry
import com.kingzcheung.xime.settings.PersonalDictManager
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SchemaMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class PersonalDictUiState(
    val selectedSchema: String = "pinyin_simp",
    val availableSchemas: List<SchemaMeta> = emptyList(),
    val entries: List<DictEntry> = emptyList(),
    val filteredEntries: List<DictEntry> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = true
)

class PersonalDictViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext

    private val _uiState = MutableStateFlow(PersonalDictUiState())
    val uiState: StateFlow<PersonalDictUiState> = _uiState.asStateFlow()

    init {
        loadSchemas()
    }

    private fun loadSchemas() {
        viewModelScope.launch {
            val schemas = withContext(Dispatchers.IO) {
                SchemaManager.discoverSchemas(context)
            }
            _uiState.update { it.copy(availableSchemas = schemas) }
            loadEntries()
        }
    }

    fun selectSchema(schemaId: String) {
        _uiState.update { it.copy(selectedSchema = schemaId, searchQuery = "") }
        loadEntries()
    }

    private fun loadEntries() {
        val schemaId = _uiState.value.selectedSchema
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val entries = withContext(Dispatchers.IO) {
                PersonalDictManager.ensureSchemaPack(context, schemaId)
                PersonalDictManager.loadEntries(context, schemaId)
            }
            _uiState.update {
                it.copy(
                    entries = entries,
                    filteredEntries = filterEntries(entries, ""),
                    searchQuery = "",
                    isLoading = false
                )
            }
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update {
            it.copy(
                searchQuery = query,
                filteredEntries = filterEntries(it.entries, query)
            )
        }
    }

    fun clearSearch() {
        setSearchQuery("")
    }

    private fun filterEntries(entries: List<DictEntry>, query: String): List<DictEntry> {
        if (query.isEmpty()) return entries
        val lowerQuery = query.lowercase(Locale.ROOT)
        return entries.filter {
            it.word.contains(query) ||
                it.code.contains(query, ignoreCase = true) ||
                it.code.lowercase(Locale.ROOT).contains(lowerQuery)
        }
    }
}
