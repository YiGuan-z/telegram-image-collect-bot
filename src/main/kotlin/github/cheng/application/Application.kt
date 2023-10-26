package github.cheng.application

import github.cheng.application.env.ApplicationConfig
import github.cheng.application.env.ConfigLoader
import github.cheng.application.env.ConfigLoader.Default.load
import github.cheng.application.env.MapApplicationConfig
import github.cheng.application.env.mergeWith
import github.cheng.engine.TelegramBot.start
import github.cheng.module.shutdown
import github.cheng.module.thisLogger
import org.slf4j.Logger

/**
 *
 * @author caseycheng
 * @date 2023/10/3-00:06
 * @doc
 **/
@BotDSL
class Application(applicationConfig: ApplicationConfig) {
    internal val appPlugins: MutableMap<AttributeKey, Any> = linkedMapOf()
    internal val commandPlugins: MutableMap<AttributeKey, Any> = linkedMapOf()
    internal val botDispatcherModules: MutableMap<AttributeKey, BotDispatcher> = linkedMapOf()
    val appEnvironment: ApplicationConfig = applicationConfig
    val logger: Logger = thisLogger<Application>()
    val isDebug = logger.isDebugEnabled

    @BotDSL
    fun <Config : Any, Plugin : Any> install(
        plugin: ApplicationPlugin<Config, Plugin>,
        configBuilder: Config.() -> Unit = {}
    ): Plugin {
        val feature = plugin.feature
        val attributeKey = plugin.attributeKey
        val pluginInstance = plugin.install(configBuilder)
        when (feature) {
            Feature.App -> putPlugin(appPlugins, attributeKey, pluginInstance)
            Feature.Command -> putPlugin(commandPlugins, attributeKey, pluginInstance)
            Feature.BotDispatcher -> putPlugin(
                botDispatcherModules,
                attributeKey,
                pluginInstance as? BotDispatcher ?: throw TypeCastException("必须实现BotDispatcher类型")
            )
        }
        return pluginInstance
    }

    @BotDSL
    fun <Config : Any, PluginA : Any> instance(pluginProvider: ApplicationPluginInstance<Config, PluginA>): PluginA {
        val attributeKey = pluginProvider.attributeKey
        val feature = pluginProvider.feature
        return instance(feature, attributeKey)
    }

    @BotDSL
    fun <PluginA> instance(feature: Feature, attributeKey: AttributeKey): PluginA {
        return when (feature) {
            Feature.App -> getPlugin(appPlugins, attributeKey)
            Feature.Command -> getPlugin(commandPlugins, attributeKey)
            Feature.BotDispatcher -> getPlugin(botDispatcherModules, attributeKey)
        }
    }

    /**
     * 如果已安装，则返回实例，如果未安装，则安装后返回实例
     */
    @BotDSL
    fun <ConfigT : Any, PluginA : Any> installAndInstance(
        pluginProvider: ApplicationPluginInstance<ConfigT, PluginA>
    ): PluginA {
        return runCatching { instance(pluginProvider) }.getOrNull() ?: install(pluginProvider)
    }

    /**
     * 检查是否已安装
     */
    @BotDSL
    fun <ConfigT : Any, PluginA : Any> checkInstall(
        pluginProvider: ApplicationPluginInstance<ConfigT, PluginA>
    ): Boolean {
        return try {
            instance(pluginProvider)
            true
        } catch (ignore: Exception) {
            false
        }
    }


    private fun <V : Any> putPlugin(plugins: MutableMap<AttributeKey, V>, attributeKey: AttributeKey, plugin: V) {
        if (attributeKey in plugins) {
            throw IllegalStateException("Plugin $attributeKey already installed.")
        }
        plugins[attributeKey] = plugin
        logger.trace("[Application] plugin {} loaded", attributeKey.key)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <PluginA, V : Any> getPlugin(plugins: Map<AttributeKey, V>, attributeKey: AttributeKey): PluginA {
        if (attributeKey !in plugins) {
            val exception = IllegalStateException("Plugin $attributeKey not installed.")
            logger.error("[Application] error", exception)
            throw exception
        }
        return plugins[attributeKey] as PluginA
    }

    companion object {
        suspend fun main(args: Array<String>, engine: ApplicationEngine, block: suspend Application.() -> Unit) {
            val environment = buildApplicationConfig(args)
            val application = Application(environment)
            with(application) {
                init()
                block()
            }
            engine.create(application)
            with(application) {
                initShutdownHook()
                this.run { start() }
            }
        }

        private val init: Application.() -> Unit = {
            //打印banner
            logger.info("application正在启动")
            banner()?.let { logger.info(it) }
        }

    }
}

context (Application)
private fun initShutdownHook() {
    install(shutdown)
        .plan()
}

context (Application)
private fun Application.banner(): String? =
    runCatching {
        this::class.java.classLoader.getResourceAsStream("banner.txt")?.readBytes()?.readToString()
    }.getOrNull()


private fun ByteArray.readToString() = String(this)

private fun buildEnvironment(args: Array<String>): ApplicationConfig {
    val list = args.mapNotNull { it.splitPair('=') }
    val configPaths = list.filter { it.first == "-config" }.map { it.second }
    val environmentConfig = getConfigFormJvmEnvironment()
    val systemEnvironment = getConfigFormSystemEnvironment()
    val config = when (configPaths.size) {
        0 -> ConfigLoader.load()
        1 -> ConfigLoader.load(configPaths.single())
        else -> configPaths.map { ConfigLoader.load(it) }.reduce { first, second -> first.mergeWith(second) }
    }
    return environmentConfig.mergeWith(config).mergeWith(MapApplicationConfig(list)).mergeWith(systemEnvironment)
}

internal fun getConfigFormJvmEnvironment(): ApplicationConfig = System.getProperties()
    .toMap()
    .let { env -> MapApplicationConfig(env.map { it.key as String to it.value as String }) }

internal fun getConfigFormSystemEnvironment(): ApplicationConfig =
    MapApplicationConfig(System.getenv().map { it.key as String to it.value as String })

internal fun buildApplicationConfig(args: Array<String>): ApplicationConfig =
    buildEnvironment(args)

internal fun String.splitPair(separator: Char): Pair<String, String>? {
    val index = indexOf(separator)
    return if (index > 0) {
        Pair(take(index), drop(index + 1))
    } else {
        null
    }
}

@BotDSL
fun <PlugConfig : Any, Plugin : Any> createAppPlugin(
    name: String,
    createConfiguration: () -> PlugConfig,
    pluginBuilder: (config: PlugConfig) -> Plugin
): ApplicationPluginInstance<PlugConfig, Plugin> =
    createPlugin(name, Feature.App, createConfiguration, pluginBuilder)

@BotDSL
fun <PlugConfig : Any, PluginT : BotDispatcher> createBotDispatcherModule(
    name: String,
    createConfiguration: () -> PlugConfig,
    pluginBuilder: (config: PlugConfig) -> PluginT
): ApplicationPluginInstance<PlugConfig, PluginT> =
    createPlugin(name, Feature.BotDispatcher, createConfiguration, pluginBuilder)

@BotDSL
fun <PlugConfig : Any, Plugin : Any> createPlugin(
    name: String,
    feature: Feature,
    createConfiguration: () -> PlugConfig,
    pluginBuilder: (config: PlugConfig) -> Plugin
): ApplicationPluginInstance<PlugConfig, Plugin> = object :
    ApplicationPluginInstance<PlugConfig, Plugin> {

    override val attributeKey: AttributeKey = AttributeKey(name)
    override val feature: Feature = feature

    override fun install(configuration: PlugConfig.() -> Unit): Plugin {
        val plugConfig = createConfiguration().apply(configuration)
        return pluginBuilder(plugConfig)
    }

}

interface ApplicationEngine {
    fun create(application: Application): Application
    fun Application.start()
    fun Application.stop()
}

