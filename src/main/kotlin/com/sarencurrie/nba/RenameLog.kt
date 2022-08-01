package com.sarencurrie.nba

import java.time.Instant
import java.util.*

data class RenameLog(
    val renamedId: String,
    val renamerId: String,
    val renamedTo: String,
    val guildId: String,
    val date: Instant,
    val messageLink: String,
)
