package top.mrxiaom.overflow.internal.contact

import cn.evole.onebot.sdk.response.contact.LoginInfoResp
import cn.evolvefield.onebot.client.core.Bot
import kotlinx.coroutines.*
import net.mamoe.mirai.LowLevelApi
import net.mamoe.mirai.Mirai
import net.mamoe.mirai.contact.*
import net.mamoe.mirai.contact.friendgroup.FriendGroups
import net.mamoe.mirai.event.EventChannel
import net.mamoe.mirai.event.GlobalEventChannel
import net.mamoe.mirai.event.events.BotEvent
import net.mamoe.mirai.internal.network.components.EventDispatcher
import net.mamoe.mirai.internal.network.components.EventDispatcherImpl
import net.mamoe.mirai.supervisorJob
import net.mamoe.mirai.utils.*
import org.java_websocket.framing.CloseFrame
import top.mrxiaom.overflow.Overflow
import top.mrxiaom.overflow.data.FriendInfoImpl
import top.mrxiaom.overflow.data.StrangerInfoImpl
import top.mrxiaom.overflow.utils.LoggerInFolder
import top.mrxiaom.overflow.utils.asCoroutineExceptionHandler
import top.mrxiaom.overflow.utils.subLogger
import top.mrxiaom.overflow.utils.update
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

@OptIn(MiraiInternalApi::class, LowLevelApi::class)
class BotWrapper private constructor(
    implBot: Bot,
    defLoginInfo: LoginInfoResp,
    botConfiguration: BotConfiguration
) : net.mamoe.mirai.Bot, CoroutineScope {
    private var implInternal = implBot
    val impl: Bot
        get() = implInternal
    private var loginInfo: LoginInfoResp = defLoginInfo
    private var friendsInternal: ContactList<FriendWrapper> = ContactList()
    private var groupsInternal: ContactList<GroupWrapper> = ContactList()
    private var otherClientsInternal: ContactList<OtherClientWrapper> = ContactList()
    private var strangersInternal: ContactList<StrangerWrapper> = ContactList()
    suspend fun updateLoginInfo() {
        loginInfo = impl.getLoginInfo().data
    }
    suspend fun updateContacts() {
        friendsInternal.update(impl.getFriendList().data.map {
            FriendWrapper(this, it)
        }) { impl = it.impl }
        groupsInternal.update(impl.getGroupList().data.map {
            GroupWrapper(this, it)
        }) { impl = it.impl }
    }
    suspend fun updateOtherClients() = runCatching {
        otherClientsInternal.update(impl.getOnlineClients(false).data.clients.map {
            OtherClientWrapper(this, it)
        }) { impl = it.impl }
    }
    internal fun updateFriend(friend: FriendWrapper): FriendWrapper {
        return ((friends[friend.id] as? FriendWrapper) ?: friend.also { friendsInternal.delegate.add(it) }).apply {
            impl = friend.impl
        }
    }
    internal fun updateStranger(stranger: StrangerWrapper): StrangerWrapper {
        return ((strangers[stranger.id] as? StrangerWrapper) ?: stranger.also { strangersInternal.delegate.add(it) }).apply {
            impl = stranger.impl
        }
    }

    override val id: Long = loginInfo.userId
    override val configuration: BotConfiguration = botConfiguration
    override val logger: MiraiLogger = configuration.botLoggerSupplier(this)
    internal val networkLogger: MiraiLogger by lazy { configuration.networkLoggerSupplier(this) }
    override val coroutineContext: CoroutineContext =
        CoroutineName("Bot.$id")
            .plus(logger.asCoroutineExceptionHandler())
            .childScopeContext(configuration.parentCoroutineContext)
            .apply {
                job.invokeOnCompletion { throwable ->
                    logger.info { "Bot cancelled" + throwable?.message?.let { ": $it" }.orEmpty() }

                    kotlin.runCatching {
                        val bot = bot
                        if (bot is BotWrapper && bot.impl.channel.isOpen) {
                            bot.close()
                        }
                    }.onFailure {
                        if (it !is CancellationException) logger.error(it)
                    }

                    @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
                    net.mamoe.mirai.Bot._instances.remove(id)

                    // help GC release instances
                    groups.forEach { it.members.delegate.clear() }
                    groups.delegate.clear() // job is cancelled, so child jobs are to be cancelled
                    friends.delegate.clear()
                    strangers.delegate.clear()
                }
            }
    override val eventChannel: EventChannel<BotEvent> =
        GlobalEventChannel.filterIsInstance<BotEvent>().filter { it.bot === this@BotWrapper }
    val eventDispatcher: EventDispatcher = EventDispatcherImpl(coroutineContext, logger.subLogger("EventDispatcher"))

    override val isOnline: Boolean
        get() = impl.channel.isOpen
    override val nick: String
        get() = loginInfo.nickname

    override val asFriend: FriendWrapper by lazy {
        Mirai.newFriend(this, FriendInfoImpl(id, nick, "", 0)).cast()
    }
    override val asStranger: StrangerWrapper by lazy {
        Mirai.newStranger(this, StrangerInfoImpl(id, bot.nick)).cast()
    }

    override val friendGroups: FriendGroups
        get() = throw NotImplementedError("Onebot 未提供好友分组接口")

    override val friends: ContactList<Friend>
        get() = friendsInternal
    override val groups: ContactList<Group>
        get() = groupsInternal
    override val otherClients: ContactList<OtherClient>
        get() = otherClientsInternal //TODO: Onebot 未提供陌生人列表接口
    override val strangers: ContactList<Stranger>
        get() = strangersInternal //TODO: Onebot 未提供陌生人列表接口

    override fun close(cause: Throwable?) {
        if (isActive) {
            if (cause == null) {
                supervisorJob.cancel()
            } else {
                supervisorJob.cancel(CancellationException("Bot closed", cause))
            }
        }
        if (impl.channel.isOpen && !impl.channel.isClosing && !impl.channel.isClosed) {
            impl.channel.close(CloseFrame.NORMAL, "主动关闭")
        }
    }

    override suspend fun login() {
        logger.warning("Bot 已由 OneBot 进行管理，溢出核心不会进行登录操作")
    }

    companion object {
        suspend fun wrap(impl: Bot, botConfiguration: BotConfiguration? = null): BotWrapper {
            val loginInfo = impl.getLoginInfo().data // also refresh bot id
            return (net.mamoe.mirai.Bot.getInstanceOrNull(impl.id) as? BotWrapper)?.apply {
                implInternal = impl
            } ?:
            BotWrapper(impl, loginInfo, botConfiguration ?: BotConfiguration {
                workingDir = File("bots/${impl.id}")
                if (Overflow.instance.miraiConsole) {
                    botLoggerSupplier = { LoggerInFolder(net.mamoe.mirai.Bot::class, "Bot.${it.id}", workingDir.resolve("logs"), 1.weeksToMillis) }
                    networkLoggerSupplier = { LoggerInFolder(net.mamoe.mirai.Bot::class, "Net.${it.id}", workingDir.resolve("logs"), 1.weeksToMillis) }
                } else {
                    botLoggerSupplier = { MiraiLogger.Factory.create(net.mamoe.mirai.Bot::class, "Bot.${it.id}") }
                    networkLoggerSupplier = { MiraiLogger.Factory.create(net.mamoe.mirai.Bot::class, "Net.${it.id}") }
                }
            }).apply {
                updateContacts()

                //updateOtherClients()
                @Suppress("INVISIBLE_MEMBER")
                net.mamoe.mirai.Bot._instances[id] = this
            }
        }
        suspend fun Bot.wrap(): BotWrapper {
            return wrap(this)
        }
    }
}