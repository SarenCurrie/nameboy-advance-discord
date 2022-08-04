package com.sarencurrie.nba

import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.util.*

class Sqlite : AutoCloseable {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:dee-emcy.sqlite")

    init {
        connection.createStatement()
            .executeUpdate("CREATE TABLE IF NOT EXISTS RenameEvents (id TEXT PRIMARY KEY, guildId TEXT, renamedId TEXT, renamerId TEXT, renamedTo TEXT, date INTEGER, messageLink TEXT)")
        connection.createStatement()
            .execute("CREATE INDEX IF NOT EXISTS guild_renamed ON RenameEvents (guildId, renamedId)")
    }

    fun save(log: RenameLog) {
        val statement = connection.prepareStatement("INSERT INTO RenameEvents VALUES (?, ?, ?, ?, ?, ?, ?)")
        statement.setString(1, UUID.randomUUID().toString())
        statement.setString(2, log.guildId)
        statement.setString(3, log.renamedId)
        statement.setString(4, log.renamerId)
        statement.setString(5, log.renamedTo)
        statement.setTimestamp(6, Timestamp.from(log.date))
        statement.setString(7, log.messageLink)
        statement.executeUpdate()
    }

    fun getRenameHistory(guildId: String, userId: String): List<Triple<String, String, String>> {
        val statement =
            connection.prepareStatement("SELECT renamerId, renamedTo, messageLink FROM RenameEvents WHERE guildId = ? AND renamedId = ? ORDER BY date ASC")
        statement.setString(1, guildId)
        statement.setString(2, userId)
        val result = statement.executeQuery()
        val output = mutableListOf<Triple<String, String, String>>()
        while (result.next()) {
            output.add(Triple(result.getString(1), result.getString(2), result.getString(3)))
        }
        return output
    }

    override fun close() {
        connection.close()
    }
}