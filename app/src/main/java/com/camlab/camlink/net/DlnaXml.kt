package com.camlab.camlink.net

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * Pure XML handling for UPnP device descriptions, SOAP Browse responses and
 * DIDL-Lite content lists. Kept free of Android classes so it can be unit
 * tested on the JVM.
 */
object DlnaXml {

    private val THUMB_MARKERS = listOf(
        "JPEG_TN", "JPEG_SM", "PNG_TN", "PNG_SM", "_TN", "_SM"
    )

    fun parseDescription(xml: String, descriptionUrl: String): CameraDevice? {
        val doc = parse(xml) ?: return null
        val device = doc.findAll("device").firstOrNull() ?: return null

        val services = device.child("serviceList")
            ?.children("service")
            ?.mapNotNull { svc ->
                val type = svc.childText("serviceType")
                val control = svc.childText("controlURL")
                if (type.isBlank() || control.isBlank()) null
                else UpnpService(type, resolveUrl(descriptionUrl, control))
            }
            .orEmpty()

        val host = runCatching { URI(descriptionUrl).host }.getOrNull().orEmpty()

        return CameraDevice(
            friendlyName = device.childText("friendlyName").trim(),
            modelName = device.childText("modelName").trim(),
            manufacturer = device.childText("manufacturer").trim(),
            ip = host,
            descriptionUrl = descriptionUrl,
            services = services
        )
    }

    fun parseBrowseResponse(xml: String, baseUrl: String): BrowsePage {
        val doc = parse(xml)
        val result = doc?.findAll("Result")?.firstOrNull()?.textContent
        val total = doc?.findAll("TotalMatches")?.firstOrNull()
            ?.textContent?.trim()?.toIntOrNull() ?: 0
        val returned = doc?.findAll("NumberReturned")?.firstOrNull()
            ?.textContent?.trim()?.toIntOrNull() ?: 0

        val content = if (result.isNullOrBlank()) {
            DidlContent(emptyList(), emptyList())
        } else {
            parseDidl(result, baseUrl)
        }
        return BrowsePage(content, total, returned)
    }

    fun parseDidl(didlXml: String, baseUrl: String): DidlContent {
        val doc = parse(didlXml) ?: return DidlContent(emptyList(), emptyList())
        val items = mutableListOf<MediaItem>()
        val containers = mutableListOf<DidlContainer>()

        for (element in doc.findAll("item")) {
            val parsed = parseItem(element, baseUrl) ?: continue
            items += parsed
        }
        for (element in doc.findAll("container")) {
            val id = element.getAttribute("id")
            if (id.isNotBlank()) {
                containers += DidlContainer(id, element.childText("title").trim())
            }
        }
        return DidlContent(items, containers)
    }

    private fun parseItem(element: Element, baseUrl: String): MediaItem? {
        val title = element.childText("title").trim()
        val date = element.childText("date").trim().ifBlank { null }

        val resources = element.children("res").mapNotNull { res ->
            val raw = res.textContent?.trim().orEmpty()
            if (raw.isBlank()) return@mapNotNull null
            val protocol = res.getAttribute("protocolInfo").orEmpty()
            val mime = protocol.split(':').getOrNull(2)?.substringBefore(';').orEmpty()
            val size = res.getAttribute("size").toLongOrNull() ?: 0L
            Resource(resolveUrl(baseUrl, raw), size, mime, protocol)
        }
        if (resources.isEmpty()) return null

        val full = resources.filter { candidate ->
            THUMB_MARKERS.none { marker -> candidate.protocol.contains(marker, true) }
        }
        val pool = if (full.isNotEmpty()) full else resources
        val best = pool.maxByOrNull { it.size } ?: pool.first()
        val thumb = resources.firstOrNull { candidate ->
            THUMB_MARKERS.any { marker -> candidate.protocol.contains(marker, true) }
        } ?: full.getOrNull(1)

        return MediaItem(
            id = best.url,
            title = title.ifBlank { best.url.substringAfterLast('/') },
            url = best.url,
            thumbUrl = thumb?.url,
            size = best.size,
            mime = best.mime,
            date = date
        )
    }

    private data class Resource(
        val url: String,
        val size: Long,
        val mime: String,
        val protocol: String
    )

    fun resolveUrl(baseUrl: String, reference: String): String {
        val ref = reference.trim()
        if (ref.startsWith("http://", true) || ref.startsWith("https://", true)) return ref
        return try {
            URI(baseUrl).resolve(URI(ref.replace(" ", "%20"))).toString()
        } catch (_: Exception) {
            val schemeEnd = baseUrl.indexOf("://")
            val pathStart = if (schemeEnd >= 0) baseUrl.indexOf('/', schemeEnd + 3) else -1
            val origin = if (pathStart > 0) baseUrl.substring(0, pathStart) else baseUrl
            origin.trimEnd('/') + "/" + ref.trimStart('/')
        }
    }

    private fun parse(xml: String): Document? = try {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching {
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            }
            runCatching {
                setFeature("http://xml.org/sax/features/external-general-entities", false)
            }
            runCatching {
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
        }
        factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    } catch (_: Exception) {
        null
    }
}

// ---- small DOM helpers -------------------------------------------------

private fun Node.localNameSafe(): String =
    (this as? Element)?.localName?.takeIf { it.isNotBlank() }
        ?: nodeName.substringAfter(':')

private fun Document.findAll(name: String): List<Element> {
    val out = ArrayList<Element>()
    fun walk(node: Node) {
        if (node is Element && node.localNameSafe() == name) out += node
        val kids = node.childNodes
        for (i in 0 until kids.length) walk(kids.item(i))
    }
    documentElement?.let { walk(it) }
    return out
}

private fun Element.children(name: String): List<Element> {
    val out = ArrayList<Element>()
    val kids = childNodes
    for (i in 0 until kids.length) {
        val child = kids.item(i)
        if (child is Element && child.localNameSafe() == name) out += child
    }
    return out
}

private fun Element.child(name: String): Element? = children(name).firstOrNull()

private fun Element.childText(name: String): String =
    child(name)?.textContent.orEmpty()
