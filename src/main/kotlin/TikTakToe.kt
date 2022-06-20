package cc.makin.tiktaktoe

import com.google.gson.annotations.SerializedName
import dev.kord.common.entity.MessageFlag
import dev.kord.common.entity.MessageFlags
import dev.kord.core.Kord
import dev.kord.core.behavior.channel.MessageChannelBehavior
import dev.kord.core.behavior.edit
import dev.kord.core.behavior.reply
import dev.kord.core.entity.Message
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.core.on
import dev.kord.gateway.Intent
import dev.kord.gateway.PrivilegedIntent
import dev.kord.rest.builder.message.AllowedMentionsBuilder
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.serialization.gson.gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.apache.logging.log4j.LogManager
import java.net.URL

val logger = LogManager.getLogger("TikTakToe")!!
const val BOT_TOKEN_ENV = "BOT_TOKEN"

fun main(): Unit = runBlocking {
    val token = System.getenv(BOT_TOKEN_ENV)
    checkNotNull(token) { "$BOT_TOKEN_ENV env variable not defined" }

    val kord = Kord(token)
    kord.on<MessageCreateEvent> {
        onMessage(this)
    }
    kord.login {
        @OptIn(PrivilegedIntent::class)
        intents += Intent.MessageContent
    }
}

private suspend fun onMessage(event: MessageCreateEvent) {
    val message = event.message
    val urls = message.content
        .split(' ')
        .filter { it.startsWith("https://") }
        .mapNotNull { runCatching { URL(it) }.getOrNull() }
        .toSet()
        .take(5)
    if (urls.isNotEmpty()) {
        replyWithDownloadUrls(urls, message)
    }
}

private suspend fun replyWithDownloadUrls(urls: Collection<URL>, message: Message) {
    val downloadUrls = runCatching { getTikTaksDownloadUrl(urls) }
        .onSuccess {
            if (it.isNotEmpty()) {
                logger.info("Extracting videos from: $it.")
                message.suppressEmbedsAsync()
            }
        }
        .onFailure { logger.error("Could not get tiktaks urls. Urls: {}", urls, it) }
    val response = downloadUrls.fold(
        onSuccess = { result -> result.joinToString(separator = "\n") { u -> u.toString() } },
        onFailure = { "unexpected error" },
    )
    if (response.isNotEmpty()) {
        message.reply {
            content = response
            allowedMentions = AllowedMentionsBuilder().apply {
                repliedUser = false
            }
        }
    }
}

private suspend fun Message.suppressEmbedsAsync() = coroutineScope {
    launch {
        edit {
            flags = flags?.minus(MessageFlag.SuppressEmbeds)
                ?: MessageFlags(MessageFlag.SuppressEmbeds)
        }
    }
}

suspend fun getTikTaksDownloadUrl(tikTaks: Collection<URL>) = coroutineScope {
    require(tikTaks.isNotEmpty()) { "empty list" }
    val videoIds = tikTaks
        .map { async { runCatching { UnshortVideoUrl.unshortVideoUrl(it) ?: it }.getOrNull() } }
        .mapNotNull { it.await() }
        .mapNotNull { AwemeId.from(it) }
    if (videoIds.isNotEmpty()) {
        VideoUrlExtractor.extract(videoIds)
    } else {
        emptyList()
    }
}

object UnshortVideoUrl {
    private val client = HttpClient(CIO) {
        followRedirects = false
    }

    suspend fun unshortVideoUrl(url: URL) = withContext(Dispatchers.IO) {
        if (url.host == "vm.tiktok.com") {
            logger.info("Unshorting url: $url.")
            val response = client.get(url)
            URL(response.headers[HttpHeaders.Location])
        } else {
            null
        }
    }
}

@JvmInline
value class AwemeId(private val id: String) {
    init {
        require(isIdValid(id))
    }

    override fun toString() = id

    companion object {
        fun from(url: URL) =
            if (url.host == "www.tiktok.com") {
                val rawId = url.path.substringAfterLast('/')
                if (isIdValid(rawId)) {
                    AwemeId(rawId)
                } else {
                    null
                }
            } else {
                null
            }

        private fun isIdValid(id: String) = id.all { it.isLetterOrDigit() }
    }
}

object VideoUrlExtractor {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            gson()
        }
    }

    suspend fun extract(videos: List<AwemeId>) =
        if (videos.isEmpty()) {
            emptyList()
        } else {
            client.get("https://api.tiktokv.com/aweme/v1/multi/aweme/detail/") {
                url {
                    val ids = videos.joinToString(separator = ", ", prefix = "[", postfix = "]")
                    parameters.append("aweme_ids", ids)
                }
            }
                .body<Response>()
                .awmeDetails
                .mapNotNull { result ->
                    result.video.playAddresses.urls.firstOrNull()
                        ?.let { runCatching { URL(it) }.getOrNull() }
                }
                .map { URL("https", it.host, it.path) }
        }

    data class Response(@SerializedName("aweme_details") val awmeDetails: List<AwmeDetails>)

    data class AwmeDetails(@SerializedName("aweme_id") val id: AwemeId, val video: Video)

    data class Video(@SerializedName("play_addr") val playAddresses: PlayAddresses)

    data class PlayAddresses(@SerializedName("url_list") val urls: List<String>)
}
