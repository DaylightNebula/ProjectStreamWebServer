package daylightnebula.projectstream.webserver

import java.io.File
import java.lang.NullPointerException

object Config {

    val settings = hashMapOf<String, String>()

    fun getBoolean(key: String, default: Boolean): Boolean {
        val value = settings[key] ?: return default
        return value.lowercase() == "true"
    }

    fun getString(key: String, default: String): String {
        val value = settings[key] ?: return default
        return value
    }

    fun getFile(key: String): File {
        val value = settings[key] ?: throw NullPointerException("Could not find config key $key")
        return File(value)
    }

    fun loadConfig() {
        // get config file
        val configfile = File(System.getProperty("user.dir"), "webconfig.txt")

        // for each line in the config file
        configfile.forEachLine { line ->
            // ignore blank lines and those starting with #
            if (line.length < 2 || line.startsWith("#")) return@forEachLine

            // get tokens of line by splitting on colons
            val tokens = line.split("=")

            // store settings by using the first token as the key and the last token as the value
            settings[tokens.first().uppercase()] = tokens.last()
        }
    }
}