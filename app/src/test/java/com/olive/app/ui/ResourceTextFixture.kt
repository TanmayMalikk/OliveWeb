// All app roles: loads localized text for Android tests.
package com.olive.app.ui

import com.olive.app.ui.shared.*

import com.olive.app.R
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

// Exercise the actual XML copy and placeholders without an Android device.
internal fun testResourceText(locale: String = "values"): ResourceText {
    val root = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
    val names = R.string::class.java.fields.associate { it.getInt(null) to it.name }
    fun read(folder: String): Map<String, String> = File(root, folder).listFiles().orEmpty()
        .filter { it.extension == "xml" }.flatMap { file ->
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                .getElementsByTagName("string")
            (0 until nodes.length).map { index ->
                val node = nodes.item(index)
                node.attributes.getNamedItem("name").nodeValue to node.textContent
                    .replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")
            }
        }.toMap()

    val values = read("values") + read(locale)
    return ResourceText { id, args ->
        val value = values.getValue(names.getValue(id))
        if (args.isEmpty()) value else String.format(Locale.ROOT, value, *args)
    }
}
