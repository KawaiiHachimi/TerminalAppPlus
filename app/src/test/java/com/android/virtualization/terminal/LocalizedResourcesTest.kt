package com.android.virtualization.terminal

import org.junit.Before
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

open class LocalizedResourcesTest {
    @Before fun initializeMessages() {
        val values = mutableMapOf<Int, String>()
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/values/plus_ui_strings.xml")).getElementsByTagName("string")
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            val id = R.string::class.java.getField(node.attributes.getNamedItem("name").nodeValue).getInt(null)
            values[id] = node.textContent.removeSurrounding("\"").replace("\\'", "'")
        }
        AppStrings.resolve = { id, args -> String.format(java.util.Locale.ENGLISH, checkNotNull(values[id]), *args) }
    }
}
