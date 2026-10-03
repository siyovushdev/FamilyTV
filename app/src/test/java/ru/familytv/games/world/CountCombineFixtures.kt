package ru.familytv.games.world

import java.io.File
import ru.familytv.games.CountCombineDefinition
import ru.familytv.games.CountCombineParser

internal object CountCombineFixtures {
    val main: File = File("src/main").takeIf { it.exists() } ?: File("app/src/main")

    fun text(name: String): String = File(main, "assets/catalog/experiences/$name").readText()

    fun apples(): CountCombineDefinition =
        requireNotNull(CountCombineParser.parse(text("count-combine-apples.json"), "game-math").definitionOrNull())

    fun baskets(): CountCombineDefinition =
        requireNotNull(CountCombineParser.parse(text("count-combine-baskets.json"), "game-math-baskets").definitionOrNull())
}

internal fun GamesState.enterMath(definition: CountCombineDefinition = CountCombineFixtures.apples()): GamesState {
    bindMath(definition)
    enter(GameWorld.MATH)
    return this
}
