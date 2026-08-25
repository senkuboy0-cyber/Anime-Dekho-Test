package com.anime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

// ─────────────────────────────────────────────
// Helpers (ported from cosmix-extensions/Free — MlsbdProvider)
// ─────────────────────────────────────────────
private fun baseUrlOf(url: String): String {
    return try {
        java.net.URI(url).let { "${it.scheme}://${it.host}" }
    } catch (e: Exception) {
        url
    }
}

private suspend fun resolveFinalRedirect(startUrl: String, referer: String? = null): String? {
    var currentUrl = startUrl
    repeat(7) {
        val res = runCatching {
            app.head(currentUrl, allowRedirects = false, timeout = 2500L, referer = referer)
        }.getOrNull() ?: return null

        val location = res.headers["location"] ?: res.headers["Location"]
        if (location.isNullOrEmpty()) return currentUrl
        currentUrl = location
    }
    return currentUrl
}

private fun qualityFromName(str: String?): Int {
    if (str.isNullOrBlank()) return Qualities.Unknown.value
    Regex("""(\d{3,4})[pP]""").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    return when {
        str.lowercase().contains("4k") -> 2160
        str.lowercase().contains("2k") -> 1440
        else -> Qualities.Unknown.value
    }
}

// ─────────────────────────────────────────────
// GDFlix (ported from cosmix-extensions/Free)
// ─────────────────────────────────────────────
open class GDFlix : ExtractorApi() {
    override val name            = "GDFlix"
    override val mainUrl         = "https://gdflix.*"
    override val requiresReferer = false

    private suspend fun cfBackupLinks(url: String): List<String> {
        val results = mutableListOf<String>()
        listOf("1", "2").forEach { t ->
            runCatching {
                val doc = app.get("$url?type=$t").document
                doc.select("a.btn-success").mapNotNullTo(results) { it.attr("href").takeIf { h -> h.isNotBlank() } }
            }
        }
        return results
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val baseUrl = baseUrlOf(url)
        val newUrl  = url

        val document = app.get(newUrl).document

        val fileName = document.select("ul > li.list-group-item:contains(Name)").text().substringAfter("Name : ").trim()
        val fileSize = document.select("ul > li.list-group-item:contains(Size)").text().substringAfter("Size : ").trim()
        val quality  = qualityFromName(fileName)

        suspend fun emit(link: String, server: String = "") {
            callback.invoke(
                newExtractorLink(
                    source  = "$name$server",
                    name    = "$name$server $fileName [$fileSize]",
                    url     = link,
                    type    = ExtractorLinkType.VIDEO
                ) {
                    this.quality = quality
                    this.headers = mapOf("Referer" to baseUrl)
                }
            )
        }

        for (anchor in document.select("div.text-center a")) {
            val text = anchor.text().trim()
            val link = anchor.attr("href")
            if (link.isBlank()) continue

            when {
                text.contains("Instant DL", ignoreCase = true) -> {
                    runCatching {
                        val location = app.get(link, allowRedirects = false).headers["location"]
                            ?: app.get(link, allowRedirects = false).headers["Location"].orEmpty()
                        var videoUrl = if (location.contains("?url=")) location.substringAfter("?url=") else location
                        if (videoUrl.isNotBlank()) {
                            if (!videoUrl.contains(".mkv", ignoreCase = true) && !videoUrl.contains(".mp4", ignoreCase = true)) {
                                videoUrl = "$videoUrl#.mkv"
                            }
                            callback.invoke(
                                newExtractorLink(
                                    source  = name,
                                    name    = "$name $fileName [$fileSize] [Instant DL]",
                                    url     = videoUrl,
                                    type    = ExtractorLinkType.VIDEO
                                ) {
                                    this.quality = quality
                                    this.headers = mapOf(
                                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36",
                                        "Referer" to ""
                                    )
                                }
                            )
                        }
                    }
                }
                text.contains("CLOUD DOWNLOAD", ignoreCase = true) -> {
                    val finalLink = if (link.startsWith("http")) link else "$baseUrl$link"
                    emit(finalLink, "[Cloud R2]")
                }
                text.contains("DIRECT", ignoreCase = true) -> {
                    val finalLink = if (link.startsWith("http")) link else "$baseUrl$link"
                    emit(finalLink, "[Direct]")
                }
                text.contains("FSL", ignoreCase = true) -> {
                    val finalLink = if (link.startsWith("http")) link else "$baseUrl$link"
                    emit(finalLink, "[FSL]")
                }
                text.contains("FAST CLOUD", ignoreCase = true) -> {
                    runCatching {
                        val targetUrl = if (link.startsWith("http")) link else "$baseUrl$link"
                        val doc = app.get(targetUrl).document
                        for (a in doc.select("div.card-body a")) {
                            var dlink = a.attr("href")
                            if (dlink.endsWith(".zip", ignoreCase = true)) {
                                dlink = dlink.dropLast(4)
                            }
                            val btnText = a.text().trim()
                            if (dlink.isNotBlank()) emit(dlink, "[Fast Cloud - $btnText]")
                        }
                    }
                }
                link.contains("pixeldra", ignoreCase = true) -> {
                    val pid      = link.substringAfterLast("/")
                    val dlBase   = baseUrlOf(link)
                    val finalURL = if (link.contains("download", ignoreCase = true)) link else "$dlBase/api/file/$pid?download"
                    emit(finalURL, "[Pixeldrain]")
                }
                text.contains("GoFile", ignoreCase = true) -> {
                    runCatching {
                        val gofileLinks = app.get(link).document.select(".row .row a").filter { it.attr("href").contains("gofile") }
                        for (it in gofileLinks) {
                            loadExtractor(it.attr("href"), "", subtitleCallback, callback)
                        }
                    }
                }
            }
        }

        runCatching {
            val wfileUrl = newUrl.replace("/file/", "/wfile/")
            for (source in cfBackupLinks(wfileUrl)) {
                val resolved = resolveFinalRedirect(source) ?: continue
                emit(resolved, "[CF Backup]")
            }
        }
    }
}

// ─────────────────────────────────────────────
// HubCloud (ported from cosmix-extensions/Free)
// ─────────────────────────────────────────────
open class HubCloud : ExtractorApi() {
    override val name: String = "Hub-Cloud"
    override val mainUrl: String = "https://hubcloud.*"
    override val requiresReferer = false

    private fun extractDoubleAtob(html: String): String? {
        val regex = Regex("""var\s+url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([^'"]+)['"]\s*\)\s*\)""")
        return regex.find(html)?.groupValues?.get(1)?.let {
            try {
                base64Decode(base64Decode(it))
            } catch (e: Exception) {
                null
            }
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val baseUrl = baseUrlOf(url)

        val doc = app.get(url).document

        var link = if (url.contains("/video/")) {
            doc.selectFirst("div.vd > center > a") ?. attr("href") ?: ""
        }
        else {
            val scriptTag = doc.selectFirst("script:containsData(url)")?.toString() ?: ""

            if (url.contains("vcloud")) {
                extractDoubleAtob(scriptTag) ?: ""
            } else {
                Regex("var url = '([^']*)'").find(scriptTag) ?. groupValues ?. get(1) ?: ""
            }
        }

        if (!link.startsWith("https://")) link = baseUrl + link

        val document = app.get(link).document
        val header = document.select("div.card-header").text()
        val size = document.select("i#size").text()
        val quality = qualityFromName(header)

        suspend fun myCallback(link: String, server: String = "") {
            callback.invoke(
                newExtractorLink(
                    "${name}${server}",
                    "${name}${server} $header[$size]",
                    link,
                    ExtractorLinkType.VIDEO
                ) {
                    this.quality = quality
                    this.headers = mapOf("Referer" to baseUrl)
                }
            )
        }

        for (it in document.select("h2 a.btn")) {
            val link = it.attr("href")
            val text = it.text()

            if (text.contains("FSL Server")) myCallback(link, "[FSL Server]")
            else if (text.contains("FSLv2")) myCallback(link, "[FSLv2 Server]")
            else if (text.contains("Mega Server")) myCallback(link, "[Mega Server]")
            else if (text.contains("Download File")) myCallback(link)
            else if (text.contains("BuzzServer")) {
                val dlink = app.get("$link/download", referer = link, allowRedirects = false).headers["hx-redirect"] ?: ""
                val buzzBase = baseUrlOf(link)
                if (dlink != "") myCallback(buzzBase + dlink, "[BuzzServer]")
            }
            else if (link.contains("pixeldra")) {
                val pixelLink = link
                val pixelBase = baseUrlOf(pixelLink)
                val finalURL = if (pixelLink.contains("download", true)) pixelLink
                else "$pixelBase/api/file/${pixelLink.substringAfterLast("/")}?download"
                myCallback(finalURL, "[Pixeldrain]")
            }
            else if (text.contains("Server : 10Gbps")) {
                var redirectUrl = resolveFinalRedirect(link, baseUrl) ?: link
                if (redirectUrl.contains("link=")) redirectUrl = redirectUrl.substringAfter("link=")
                myCallback(redirectUrl, "[Download]")
            }
            else if (text.contains("Gofile")) loadExtractor(link, "", subtitleCallback, callback)
            else { println("No Server matched") }
        }
    }
}
