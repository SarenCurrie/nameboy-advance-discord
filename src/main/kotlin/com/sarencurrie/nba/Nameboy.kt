package com.sarencurrie.nba

import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.cache.CacheFlag
import java.time.Instant
import java.util.concurrent.TimeUnit

fun main() {
    val client = JDABuilder.create(
        System.getenv("NBA_DISCORD_TOKEN"),
        GatewayIntent.GUILD_MEMBERS,
        GatewayIntent.GUILD_MESSAGES,
        GatewayIntent.DIRECT_MESSAGES
    )
        .disableCache(
            CacheFlag.ACTIVITY,
            CacheFlag.CLIENT_STATUS,
            CacheFlag.EMOTE,
            CacheFlag.MEMBER_OVERRIDES,
            CacheFlag.VOICE_STATE
        )
        .addEventListeners(CommandListener(Sqlite()))
        .build()
    client.awaitReady()
}

class CommandListener(val sqlite: Sqlite) : ListenerAdapter() {
    override fun onMessageReceived(event: MessageReceivedEvent) {
        val messageParts = event.message.contentRaw.split(" ", limit = 4)
        if (!messageParts[0].matches(Regex("<@!?${event.jda.selfUser.id}>"))) {
            return
        }
        val command = messageParts[1]
        if (command in commands) {
            commands[command]?.invoke(messageParts, event)
        } else {
            println("Trying to complain that I can't do stuff")
            event.channel.sendMessage("I don't know how to $command")
                .delay(10, TimeUnit.SECONDS)
                .flatMap(Message::delete)
                .complete()
        }
    }

    private val commands = mapOf<String, (List<String>, MessageReceivedEvent) -> Any>(
        "help" to { _, event ->
            event.channel
                .sendMessage("Usage: ${event.jda.selfUser.asMention} rename @Someone Something")
                .delay(10, TimeUnit.SECONDS)
                .flatMap(Message::delete)
                .complete()
        },
        "rename" to fun(args: List<String>, event: MessageReceivedEvent) {
            try {
                rename(args[2], args[3], event)
            } catch (e: Exception) {
                e.printStackTrace()
                event.channel.sendMessage("**Error:** ${e.message}").complete()
                return
            }
            event.channel
                .sendMessage("Okay, I'll try rename ${args[2]} to ${args[3]}")
                .delay(10, TimeUnit.SECONDS)
                .flatMap(Message::delete)
                .complete()
            return
        },
        "history" to fun(args, event) {
            try {
                val server = event.guild
                val member = mentionToMember(args[2], server)
                val renameLog = sqlite.getRenameHistory(server.id, member.id)
                if (renameLog.isEmpty()) {
                    event.channel.sendMessage("I don't remember renaming them in this server.").complete()
                    return
                }
                val b = StringBuilder()
                b.append("**Rename history for ${member.effectiveName}:**\n")
                b.append("Renamer | Renamed to | Link\n")
                renameLog.forEach {
                    b.append(idToCurrentName(it.first, server) ?: "Unknown \uD83E\uDEE5")
                        .append(" | ")
                        .append(it.second)
                        .append(" | ")
                        .append(it.third)
                        .append("\n")
                }
                event.channel.sendMessage(b.toString()).complete()
            } catch (e: Exception) {
                e.printStackTrace()
                event.channel.sendMessage("**Error:** ${e.message}").complete()
                return
            }
        }
    )

    private fun rename(user: String, name: String, event: MessageReceivedEvent) {
        val server = event.guild
        val member = mentionToMember(user, server)
        server
            .modifyNickname(member, name)
            .reason("On behalf of ${event.author.asTag}")
            .complete()
        sqlite.save(RenameLog(member.id, event.author.id, name, server.id, Instant.now(), event.message.jumpUrl))
    }
}

private fun mentionToMember(user: String, server: Guild): Member =
    Regex("<@!?(\\d+)>").find(user)?.groupValues?.get(1)?.let { server.getMemberById(it) }
        ?: throw RuntimeException("Cannot find $user")

private fun idToCurrentName(id: String, server: Guild): String? = server.getMemberById(id)?.effectiveName