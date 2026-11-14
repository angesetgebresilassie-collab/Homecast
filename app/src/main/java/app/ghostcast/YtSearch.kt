package app.ghostcast

import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem

data class VideoHit(val title: String, val channel: String, val url: String, val seconds: Long)

/** In-app YouTube search (videos only) via NewPipeExtractor. Call off the main thread. */
object YtSearch {
    fun search(query: String): List<VideoHit> {
        val ex = ServiceList.YouTube.getSearchExtractor(query, listOf("videos"), "")
        ex.fetchPage()
        return ex.initialPage.items.filterIsInstance<StreamInfoItem>()
            .map { VideoHit(it.name, it.uploaderName ?: "", it.url, it.duration) }
    }
}
