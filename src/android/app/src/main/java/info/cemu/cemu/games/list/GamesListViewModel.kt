package info.cemu.cemu.games.list

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import info.cemu.cemu.nativeinterface.NativeGameTitles
import info.cemu.cemu.nativeinterface.NativeGameTitles.Game
import info.cemu.cemu.nativeinterface.NativeSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

enum class GameSortMode {
    Name,
    LastPlayed,
    PlayTime,
}

class GamesListViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("games_list", Context.MODE_PRIVATE)

    private val _sortMode = MutableStateFlow(
        GameSortMode.entries.firstOrNull { it.name == preferences.getString(SORT_MODE_KEY, null) }
            ?: GameSortMode.Name
    )
    val sortMode = _sortMode.asStateFlow()
    fun setSortMode(sortMode: GameSortMode) {
        _sortMode.value = sortMode
        preferences.edit().putString(SORT_MODE_KEY, sortMode.name).apply()
    }

    private var gamePaths = NativeSettings.getGamesPaths().toSet()

    private val _filterText = MutableStateFlow("")
    val filterText = _filterText.asStateFlow()
    fun setFilterText(filterText: String) {
        _filterText.value = filterText
    }

    private val _games = MutableStateFlow<Set<Game>>(emptySet())
    val games: StateFlow<List<Game>> = combine(_filterText, _games, _sortMode) { filter, games, sortMode ->
        val filtered = if (filter.isBlank()) {
            games
        } else {
            games.filter { it.name?.contains(filter, true) ?: false }
        }
        sortGames(filtered, sortMode)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    init {
        NativeGameTitles.setGameTitleLoadedCallback(NativeGameTitles.GameTitleLoadedCallback { game: Game ->
            if (_games.value.any { it.titleId == game.titleId || it.path == game.path })
                return@GameTitleLoadedCallback

            _games.value += game
        })
        refreshGames()
    }

    fun removeShadersForGame(game: Game) {
        NativeGameTitles.removeShaderCacheFilesForTitle(game.titleId)
    }

    fun setGameTitleFavorite(game: Game, isFavorite: Boolean) {
        if (!_games.value.contains(game)) {
            return
        }
        NativeGameTitles.setGameTitleFavorite(game.titleId, isFavorite)
        _games.value = _games.value.toMutableSet().apply {
            remove(game)
            add(game.copy(isFavorite = isFavorite))
        }
    }

    override fun onCleared() {
        NativeGameTitles.setGameTitleLoadedCallback(null)
    }

    fun gamePathsHaveChanged(): Boolean {
        val newGamePaths = NativeSettings.getGamesPaths().toSet()
        if (newGamePaths != gamePaths) {
            gamePaths = newGamePaths
            return true
        }
        return false
    }

    companion object {
        private const val SORT_MODE_KEY = "sort_mode"

        private fun lastPlayedKey(game: Game): Int =
            game.lastPlayedYear * 10000 + game.lastPlayedMonth * 100 + game.lastPlayedDay

        // Favorites stay on top in every mode; ties fall back to the name order.
        fun sortGames(games: Collection<Game>, sortMode: GameSortMode): List<Game> = when (sortMode) {
            GameSortMode.Name -> games.sorted()
            GameSortMode.LastPlayed -> games.sortedWith(
                compareByDescending<Game> { it.isFavorite }
                    .thenByDescending { lastPlayedKey(it) }
                    .then(naturalOrder())
            )

            GameSortMode.PlayTime -> games.sortedWith(
                compareByDescending<Game> { it.isFavorite }
                    .thenByDescending { it.minutesPlayed }
                    .then(naturalOrder())
            )
        }
    }

    fun refreshGames() {
        _games.value = emptySet()
        NativeGameTitles.reloadGameTitles()
    }
}
