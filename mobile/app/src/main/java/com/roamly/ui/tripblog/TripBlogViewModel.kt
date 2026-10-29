package com.roamly.ui.tripblog

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roamly.data.api.TripCommentDto
import com.roamly.data.api.TripDay
import com.roamly.data.api.TripMyLocation
import com.roamly.data.api.TripPostDto
import com.roamly.data.api.TripStatus
import com.roamly.data.prefs.UserPreferences
import com.roamly.data.repository.Result
import com.roamly.data.repository.TripBlogRepository
import com.roamly.tripblog.TripPostOutbox
import com.roamly.tripblog.TripPostWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class TripBlogUiState(
    val blogId: Int? = null,
    val serverUrl: String = "",
    val status: TripStatus? = null,
    val days: List<TripDay> = emptyList(),
    val nextBefore: String? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val pendingPosts: Int = 0,
)

/** A media item picked in the composer, with what its EXIF said. */
data class PickedMedia(val uri: Uri, val kind: String)

data class ComposerState(
    val text: String = "",
    val media: List<PickedMedia> = emptyList(),
    val lat: Double? = null,
    val lon: Double? = null,
    val locLabel: String? = null,
    val takenAt: String? = null,      // ISO with offset, from EXIF
    val takenLabel: String? = null,
    val busy: Boolean = false,
)

/**
 * Activity-scoped (RoamlyNavHost): one-shot loads only, no continuous
 * collectors — the rule that keeps it off the capture loop's back. The screen
 * calls [load] once per visit.
 */
@HiltViewModel
class TripBlogViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: TripBlogRepository,
    private val prefs: UserPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(TripBlogUiState())
    val state: StateFlow<TripBlogUiState> = _state

    private val _composer = MutableStateFlow(ComposerState())
    val composer: StateFlow<ComposerState> = _composer

    fun load() {
        viewModelScope.launch {
            val id = prefs.featuredTripId.first() ?: repo.refreshFeatured()?.id ?: run {
                _state.update { it.copy(blogId = null, error = "No trip is set up yet.") }
                return@launch
            }
            val switched = _state.value.blogId != id
            _state.update {
                it.copy(blogId = id, serverUrl = prefs.serverUrl.first().orEmpty(),
                    loading = it.days.isEmpty() || switched, error = null,
                    pendingPosts = TripPostOutbox.count(context),
                    days = if (switched) emptyList() else it.days,
                    status = if (switched) null else it.status)
            }
            if (TripPostOutbox.count(context) > 0) TripPostWorker.schedule(context)
            refresh(id)
            // The featured trip may have changed on the web.
            repo.refreshFeatured()
        }
    }

    private suspend fun refresh(id: Int) {
        val st = repo.status(id)
        val fd = repo.feed(id, null)
        _state.update { s ->
            s.copy(
                status = (st as? Result.Success)?.data ?: s.status,
                days = (fd as? Result.Success)?.data?.days ?: s.days,
                nextBefore = (fd as? Result.Success)?.data?.nextBefore ?: if (fd is Result.Success) null else s.nextBefore,
                loading = false,
                error = (fd as? Result.Error)?.message?.takeIf { s.days.isEmpty() },
            )
        }
    }

    fun reload() {
        val id = _state.value.blogId ?: return load()
        viewModelScope.launch {
            _state.update { it.copy(pendingPosts = TripPostOutbox.count(context)) }
            refresh(id)
        }
    }

    fun loadMore() {
        val s = _state.value
        val id = s.blogId ?: return
        val before = s.nextBefore ?: return
        if (s.loadingMore) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            when (val r = repo.feed(id, before)) {
                is Result.Success -> _state.update { st ->
                    val known = st.days.map { it.date }.toSet()
                    st.copy(days = st.days + r.data.days.filter { it.date !in known },
                        nextBefore = r.data.nextBefore, loadingMore = false)
                }
                is Result.Error -> _state.update { it.copy(loadingMore = false) }
            }
        }
    }

    private fun updatePost(postId: Int, f: (TripPostDto) -> TripPostDto) {
        _state.update { s ->
            s.copy(days = s.days.map { d -> d.copy(posts = d.posts.map { if (it.id == postId) f(it) else it }) })
        }
    }

    fun react(post: TripPostDto, emoji: String) {
        val id = _state.value.blogId ?: return
        viewModelScope.launch {
            val r = repo.react(id, post.id, emoji)
            if (r is Result.Success) updatePost(post.id) { p ->
                p.copy(reactions = r.data.reactions,
                    myReactions = if (r.data.on) (p.myReactions + emoji).distinct() else p.myReactions - emoji)
            }
        }
    }

    fun comment(post: TripPostDto, text: String, onDone: (Boolean) -> Unit) {
        val id = _state.value.blogId ?: return
        viewModelScope.launch {
            when (val r = repo.comment(id, post.id, text)) {
                is Result.Success -> {
                    updatePost(post.id) { it.copy(comments = it.comments + r.data, commentCount = it.commentCount + 1) }
                    onDone(true)
                }
                is Result.Error -> onDone(false)
            }
        }
    }

    fun loadAllComments(post: TripPostDto) {
        val id = _state.value.blogId ?: return
        viewModelScope.launch {
            val r = repo.comments(id, post.id)
            if (r is Result.Success) updatePost(post.id) { it.copy(comments = r.data, commentCount = r.data.size) }
        }
    }

    fun deleteComment(post: TripPostDto, c: TripCommentDto) {
        val id = _state.value.blogId ?: return
        viewModelScope.launch {
            if (repo.deleteComment(id, c.id) is Result.Success)
                updatePost(post.id) { p -> p.copy(comments = p.comments.filter { it.id != c.id }, commentCount = p.commentCount - 1) }
        }
    }

    fun deletePost(post: TripPostDto) {
        val id = _state.value.blogId ?: return
        viewModelScope.launch {
            if (repo.deletePost(id, post.id) is Result.Success) {
                _state.update { s -> s.copy(days = s.days.map { d -> d.copy(posts = d.posts.filter { it.id != post.id }) }
                    .filter { it.posts.isNotEmpty() }) }
            }
        }
    }

    // ── composer ────────────────────────────────────────────────────────────

    fun setText(t: String) = _composer.update { it.copy(text = t) }

    fun addMedia(items: List<PickedMedia>) {
        _composer.update { it.copy(media = it.media + items) }
        // The first photo with GPS/time fills the chips, as on the web.
        viewModelScope.launch(Dispatchers.IO) {
            if (_composer.value.lat != null && _composer.value.takenAt != null) return@launch
            for (m in items.filter { it.kind == "image" }) {
                val ex = MediaExif.read(context, m.uri) ?: continue
                _composer.update { c ->
                    c.copy(
                        lat = c.lat ?: ex.lat, lon = c.lon ?: ex.lon,
                        locLabel = c.locLabel ?: if (ex.lat != null) "Where the photo was taken" else null,
                        takenAt = c.takenAt ?: ex.takenAt, takenLabel = c.takenLabel ?: ex.takenLabel,
                    )
                }
                if (_composer.value.lat != null) break
            }
        }
    }

    fun removeMedia(m: PickedMedia) = _composer.update { it.copy(media = it.media - m) }

    fun clearLocation() = _composer.update { it.copy(lat = null, lon = null, locLabel = null) }

    fun clearTime() = _composer.update { it.copy(takenAt = null, takenLabel = null) }

    fun setPicked(lat: Double, lon: Double) =
        _composer.update { it.copy(lat = lat, lon = lon, locLabel = "Picked on the map") }

    fun useMyLocation(onMissing: () -> Unit) {
        val id = _state.value.blogId ?: return
        viewModelScope.launch {
            val loc: TripMyLocation? = repo.myLocation(id)
            if (loc == null) { onMissing(); return@launch }
            val ago = if (loc.ageS > 3600) " (${loc.ageS / 3600} h ago)" else ""
            _composer.update { it.copy(lat = loc.lat, lon = loc.lon, locLabel = (loc.city.ifBlank { "Current location" }) + ago) }
        }
    }

    fun resetComposer() { _composer.value = ComposerState() }

    /** Copy the media into the outbox and hand off to the worker. */
    fun submit(onQueued: () -> Unit) {
        val id = _state.value.blogId ?: return
        val c = _composer.value
        if (c.text.isBlank() && c.media.isEmpty()) return
        _composer.update { it.copy(busy = true) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                TripPostOutbox.enqueue(context, id, c.text.trim(), c.lat, c.lon, c.takenAt,
                    c.media.map { it.uri to it.kind })
            }
            TripPostWorker.schedule(context)
            _composer.value = ComposerState()
            _state.update { it.copy(pendingPosts = TripPostOutbox.count(context)) }
            onQueued()
        }
    }
}
