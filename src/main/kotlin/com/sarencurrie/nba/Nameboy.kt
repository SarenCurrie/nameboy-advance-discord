package com.sarencurrie.nba

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.interaction.SlashCommandEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.cache.CacheFlag
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.xml.transform.Source

fun main() {
    val client = JDABuilder
        .create(
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
    client.guilds.forEach{
        it.upsertCommand("rename", "Renames a user")
            .addOption(OptionType.USER, "user", "The user to rename", true)
            .addOption(OptionType.STRING, "nickname", "What to rename the user to", true)
            .setDefaultEnabled(true)
            .queue()
        it.upsertCommand("history", "Histories a user")
            .addOption(OptionType.USER, "user", "The user to history", true)
            .setDefaultEnabled(true)
            .queue()
    }

}

class ExpectedException(message: String): Exception(message)

class CommandListener(private val sqlite: Sqlite) : ListenerAdapter() {
    override fun onMessageReceived(event: MessageReceivedEvent) {
        val messageParts = event.message.contentRaw.split(" ", limit = 4)
        if (!messageParts[0].matches(Regex("<@!?${event.jda.selfUser.id}>"))) {
            return
        }
        val command = messageParts[1]
        if (command in messageCommands) {
            messageCommands[command]?.invoke(messageParts, event)
        } else {
            println("Trying to complain that I can't do stuff")
            event.channel.sendMessage("I don't know how to $command")
                .delay(10, TimeUnit.SECONDS)
                .flatMap(Message::delete)
                .complete()
        }
    }

    private val messageCommands = mapOf<String, (List<String>, MessageReceivedEvent) -> Any>(
        "help" to { _, event ->
            event.channel
                .sendMessage("Usage: ${event.jda.selfUser.asMention} rename @Someone Something")
                .delay(10, TimeUnit.SECONDS)
                .flatMap(Message::delete)
                .complete()
        },
        "rename" to fun(args: List<String>, event: MessageReceivedEvent) {
            try {
                rename(args[2], args[3], event.guild, event.author, event.message)
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
        },
        "history" to fun(args, event) {
            try {
                val server = event.guild
                val member = mentionToMember(args[2], server)
                val embed = getRenameHistory(member, server)
                event.channel.sendMessageEmbeds(listOf(embed)).queue()
            } catch (e: ExpectedException) {
                event.channel.sendMessage(e.message ?: "Unknown error!").queue()
            } catch (e: Exception) {
                e.printStackTrace()
                event.channel.sendMessage("**Error:** ${e.message}").complete()
            }
        }
    )

    override fun onSlashCommand(event: SlashCommandEvent) {
        slashCommands[event.name]?.invoke(event.options, event)
    }

    private val slashCommands = mapOf<String, (List<OptionMapping>, SlashCommandEvent) -> Any>(
        "rename" to fun(args, event) {
            event.deferReply().setEphemeral(false).queue()
            try {
                val mention = args[0].asUser.asMention
                val renameTo = args[1].asString
                val responseMessage = event.hook.sendMessage("Renaming $mention to $renameTo").complete()
                rename(mention, renameTo, event.guild!!, event.user, responseMessage)
            } catch (e: Exception) {
                e.printStackTrace()
                event.hook.sendMessage("**Error:** ${e.message}").complete()
            }
            return
        },
        "history" to fun(args, event) {
            event.deferReply().setEphemeral(false).queue()
            try {
                val server = event.guild!!
                val member = args[0].asMember
                if (member == null) {
                    event.hook.sendMessage("I can't find that user").queue()
                    return
                }
                val embed = getRenameHistory(member, server)
                event.hook.setEphemeral(false).sendMessageEmbeds(listOf(embed))
                    .queue()
            } catch (e: ExpectedException) {
                event.hook.setEphemeral(false).sendMessage(e.message ?: "Unknown error!").queue()
            } catch (e: Exception) {
                e.printStackTrace()
                event.hook.setEphemeral(false).sendMessage("**Error:** ${e.message}").queue()
            }
        }
    )

    private fun getRenameHistory(member: Member, server: Guild): MessageEmbed {
        val renameLog = sqlite.getRenameHistory(server.id, member.id)
        if (renameLog.isEmpty()) {
            throw ExpectedException("I don't remember renaming them in this server.")
        }
        val e = EmbedBuilder()
        e.setTitle("Rename history for ${member.effectiveName}")
        renameLog.forEach {
            e.addField((idToCurrentName(it.first, server) ?: "Unknown \uD83E\uDEE5") + " renamed to ${it.second}", it.third, false)
        }
        return e.build()
    }

    private fun rename(user: String, name: String, server: Guild, author: User, source: Message) {
        val member = mentionToMember(user, server)
        server
            .modifyNickname(member, name)
            .reason("On behalf of ${author.asTag}")
            .complete()
        sqlite.save(RenameLog(member.id, author.id, name, server.id, Instant.now(), source.jumpUrl))
    }
}

private fun mentionToMember(user: String, server: Guild): Member =
    Regex("<@!?(\\d+)>").find(user)?.groupValues?.get(1)?.let { server.getMemberById(it) }
        ?: throw RuntimeException("Cannot find $user")

private fun idToCurrentName(id: String, server: Guild): String? = server.getMemberById(id)?.effectiveName