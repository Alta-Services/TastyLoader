package me.tastycake

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.wait
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.serialization.ConfigurationSerialization
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit


/**
 * @author Cupoftea & Tasty Cake
 * @date 9/15/2024
 */


class TastyLoader : JavaPlugin() {
    companion object {
        @JvmStatic
        private var instance: TastyLoader? = null
    }

    private lateinit var githubToken: String

    override fun onEnable() {
        instance = this
        ConfigurationSerialization.registerClass(Loadable::class.java)

        saveDefaultConfig()
        val config: FileConfiguration = this.config

        // Load GitHub token from config
        githubToken = config.getString("github_token") ?: ""

        val repo = config.getString("repo") ?: throw IllegalStateException("Repo Url is not specified in config")
        val loadables = getLoadablesFromConfig(config)

        val sortedLoadables = loadables.values.sortedBy { it.priority }

        for (loadable in sortedLoadables) {
            if (loadable.enable) {
                try {
                    downloadPlugin(repo, loadable.jarName)
                        .thenAccept {
                            loadPlugin(File("$dataFolder/loaded", "${loadable.jarName}.jar"))
                                .get(10, TimeUnit.SECONDS)
                        }
                } catch (e: Exception) {
                    logger.severe("Failed to load plugin ${loadable.jarName}: ${e.message}")
                }
            }
        }
    }

    private fun downloadPlugin(repo: String, jarName: String): CompletableFuture<File> {
        val future = CompletableFuture<File>()
        object : BukkitRunnable() {
            override fun run() {
                val url = URL("$repo/$jarName.jar")
                val connection = url.openConnection() as HttpURLConnection
                if (githubToken.isNotEmpty()) {
                    val authHeader = "Bearer $githubToken"
                    connection.setRequestProperty("Authorization", authHeader)
                }
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val inputStream = connection.inputStream
                    val destination = File("$dataFolder/loaded", "$jarName.jar")
                    Files.createDirectories(destination.parentFile.toPath())

                    if (destination.exists()) {
                        destination.delete()
                    }

                    val outputStream = destination.outputStream()

                    try {
                        inputStream.use { input ->
                            outputStream.use { output ->
                                input.copyTo(output)
                            }
                        }
                    } catch (e: Exception) {
                        logger.severe("Failed to download or write file: ${e.message}")
                        throw e
                    }

                    logger.info("Downloaded $jarName.jar")
                    future.complete(destination)
                } else {
                    logger.severe("HTTP error code: $responseCode")
                    throw IllegalStateException("Failed to download file: HTTP error code $responseCode")
                }
            }
        }.runTaskAsynchronously(this)

        return future
    }

    private fun loadPlugin(file: File) : CompletableFuture<Unit> {
        val future = CompletableFuture<Unit>()

        object : BukkitRunnable() {
            override fun run() {
                try {
                    val plugin = server.pluginManager.loadPlugin(file)
                    if (plugin != null) {
                        future.complete(server.pluginManager.enablePlugin(plugin))
                        logger.info("Loaded and enabled ${plugin.name}")
                    } else {
                        logger.warning("Failed to load plugin from file ${file.name}")
                    }
                } catch (e: Exception) {
                    logger.severe("Error loading plugin from file ${file.name}: ${e.message}")
                }
            }
        }.runTaskAsynchronously(this)

        return future
    }

    private fun unloadPlugin(pluginName: String) {
        val plugin = server.pluginManager.getPlugin(pluginName)
        if (plugin != null) {
            server.pluginManager.disablePlugin(plugin)
            try {
                val classLoader = plugin.javaClass.classLoader
                if (classLoader is URLClassLoader) {
                    classLoader.close()
                }
                logger.info("Unloaded plugin $pluginName")
            } catch (e: Exception) {
                logger.severe("Error unloading plugin $pluginName: ${e.message}")
            }
        } else {
            logger.warning("Plugin $pluginName not found or already unloaded")
        }
    }

    fun unloadSpecificPlugin(pluginName: String) {
        unloadPlugin(pluginName)
    }

    override fun onDisable() {
        val folder = File("$dataFolder/loaded")
        folder.deleteRecursively()

        val config: FileConfiguration = this.config
        val loadables = getLoadablesFromConfig(config)

        for (loadable in loadables.values) {
            if (loadable.enable) {
                unloadPlugin(loadable.jarName)
            }
        }
    }

    private fun getLoadablesFromConfig(config: FileConfiguration): Map<String, Loadable> {
        val loadablesSection = config.getConfigurationSection("loadables")
        return loadablesSection?.getKeys(false)?.associateWith { key ->
            val section = loadablesSection.getConfigurationSection(key)
            Loadable(
                jarName = section?.getString("jarName") ?: "",
                priority = section?.getInt("priority") ?: 0,
                enable = section?.getBoolean("enabled") ?: true
            )
        } ?: emptyMap()
    }
}