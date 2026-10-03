package ru.familytv.games.world

import java.io.File
import ru.familytv.games.AttributeApplyDefinition
import ru.familytv.games.AttributeApplyParser

internal object AttributeApplyFixtures {
    val main: File = File("src/main").takeIf { it.exists() } ?: File("app/src/main")

    fun text(name: String): String = File(main, "assets/catalog/experiences/$name").readText()

    fun rocket(): AttributeApplyDefinition =
        requireNotNull(AttributeApplyParser.parse(text("attribute-apply-rocket.json"), "game-color").definitionOrNull())

    fun sun(): AttributeApplyDefinition =
        requireNotNull(AttributeApplyParser.parse(text("attribute-apply-sun.json"), "game-color-sun").definitionOrNull())
}

internal fun GamesState.enterColor(definition: AttributeApplyDefinition = AttributeApplyFixtures.rocket()): GamesState {
    bindColor(definition)
    enter(GameWorld.COLOR)
    return this
}
