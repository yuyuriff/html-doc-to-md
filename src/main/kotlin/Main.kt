package org.example
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.walk

// deletes all previous contents and creates folder
fun folderInit(dir: Path) {
    try {
        if (dir.exists()) {
            dir.walk().forEach { Files.deleteIfExists(it) }
        }
        Files.createDirectories(dir)
    } catch (e: Exception) {
        throw RuntimeException("FATAL: Could not initialize folder $dir", e)
    }
}

fun downloadImg(
    url: String,
    path: Path,
): Boolean {
    try {
        URI.create(url).toURL().openStream().use { input ->
            Files.copy(input, path, StandardCopyOption.REPLACE_EXISTING)
        }
        return true
    } catch (_: Exception) {
        return false
    }
}

// simple heuristics for code block syntax highlighting
fun guessCodeLanguage(code: String): String {
    if (code.startsWith("$")) {
        return "bash"
    } else if (code.contains(";")) {
        return "java"
    }
    return "text"
}

fun fetch(url: String): Document =
    try {
        Jsoup
            .connect(url)
            .timeout(10000)
            .get()
    } catch (e: Exception) {
        throw RuntimeException("FATAL: Could not fetch html: $url", e)
    }

// links conversion. # links are kept for md navigation
fun aHrefToMd(elem: Element): String {
    val sb = StringBuilder()
    val text = elem.text().trim()
    val attrHref = elem.attr("href")
    val absHref = elem.absUrl("href")

    val href =
        when {
            attrHref.startsWith("#") -> attrHref
            absHref.isNotBlank() -> absHref
            else -> attrHref
        }

    if (href.isNotBlank()) {
        sb.append("[$text]").append("($href)")
    } else {
        sb.append(text)
    }

    return sb.toString()
}

fun codeToMd(elem: Element): String {
    val sb = StringBuilder()
    val code = elem.text()
    sb
        .append("```${guessCodeLanguage(code)}\n")
        .append(code)
        .append("\n```")
    return sb.toString()
}

// table conversion. assumes <thead> and <tbody> are present
// uses mediaobject class to determine 'fake' tables
// complex tables are left in HTML
fun tableToMd(table: Element): String {
    if (table.parent()?.className() == "mediaobject") {
        return ""
    }

    val sb = StringBuilder()

    if (table.select("[rowspan],[colspan]").isNotEmpty()) {
        sb.append(table.outerHtml()).append("\n")
        return sb.toString()
    }

    val thead = table.selectFirst("thead")
    val tbody = table.selectFirst("tbody")

    val headers = thead?.select("th")?.map { it.text().trim() }
    val rows = tbody?.select("tr")

    sb.append("| ").append(headers?.joinToString(" | ")).append(" |\n")
    headers?.forEach { sb.append("| --- ") }
    sb.append("|\n")

    for (i in 0 until (rows?.size ?: 0)) {
        val cells = rows?.get(i)!!.select("td").map { it.text().trim() }
        sb.append("| ").append(cells.joinToString(" | ")).append(" |\n")
    }

    return sb.toString()
}

fun innerTextToMd(elem: Element): String {
    val sb = StringBuilder()

    for (child in elem.childNodes()) {
        when (child) {
            is Element -> {
                when (child.tagName()) {
                    "br" -> sb.append("\n")
                    "a" -> sb.append(aHrefToMd(child))
                    "span" -> sb.append(innerTextToMd(child))
                    "b" -> sb.append("**").append(child.text()).append("**")
                    else -> sb.append(child.text())
                }
            }

            is org.jsoup.nodes.TextNode -> {
                sb.append(child.text())
            }
        }
    }

    return sb.toString()
}

// header conversion + link for md navigation
fun headerToMd(
    elem: Element,
    tag: String,
): String {
    val sb = StringBuilder()

    val link = elem.selectFirst("a[name]")?.attr("name")
    if (link != null) {
        sb.append("<a id=\"${link}\"></a>\n")
    }

    when (tag) {
        "h1" -> sb.append("# ").append(elem.text()).append("\n\n")
        "h2" -> sb.append("## ").append(elem.text()).append("\n\n")
        "h3" -> sb.append("### ").append(elem.text()).append("\n\n")
        "h4" -> sb.append("#### ").append(elem.text()).append("\n\n")
    }

    return sb.toString()
}

// downloads image into assets dir and links in md
// if download fails src is inserted
fun imgToMd(
    elem: Element,
    assetsDir: Path,
): String {
    val sb = StringBuilder()

    val altText = elem.attr("alt").trim().ifEmpty { "" }
    val src = elem.absUrl("src").trim()
    val fileName = src.substringAfterLast("/")

    val result = downloadImg(src, assetsDir.resolve(fileName))

    if (result) {
        sb.append("![$altText]").append("(/${assetsDir.fileName}/$fileName)")
    } else {
        println("WARN: Could not download img: $src")
        sb.append("![$altText]").append("($src)")
    }
    return sb.toString()
}

fun parseToMd(
    doc: Document,
    assetsDir: Path,
): String {
    val sb = StringBuilder()

    val elements = doc.body().select("h1, h2, h3, h4, p, pre, dt, table, img")

    for (elem in elements) {
        when (elem.tagName()) {
            "h1", "h2", "h3", "h4" -> {
                sb.append(headerToMd(elem, elem.tagName()))
            }

            "p" -> {
                sb.append(innerTextToMd(elem)).append("\n\n")
            }

            "dt" -> {
                sb.append(innerTextToMd(elem)).append("\n\n")
            }

            "pre" -> {
                sb.append(codeToMd(elem)).append("\n\n")
            }

            "table" -> {
                sb.append(tableToMd(elem)).append("\n")
            }

            "img" -> {
                sb.append(imgToMd(elem, assetsDir)).append("\n")
            }
        }
    }

    return sb.toString()
}

fun main() {
    val assetsDir = Path.of("assets")
    val url = "https://opennlp.apache.org/docs/2.5.7/manual/opennlp.html"
    try {
        val doc = fetch(url)
        folderInit(assetsDir)
        val contents = parseToMd(doc, assetsDir)

        val fileName = "opennlp_manual.md"
        File(fileName).writeText(contents)

        println("SUCCESS: documentation from $url saved to $fileName")
    } catch (e: Exception) {
        println(e.message)
        return
    }
}
