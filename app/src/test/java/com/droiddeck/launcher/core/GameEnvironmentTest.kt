package com.droiddeck.launcher.core

import org.junit.Assert.*
import org.junit.Test

class GameEnvironmentTest {
    private fun values(config: GameEnvironment.Config, preset: String, scope: String) =
        GameEnvironment.resolve(config, preset, scope).associate { it.name to it.value }

    @Test fun defaultsMatchTheValidatedTurnipSetupAndKeepCaching() {
        val values = GameEnvironment.defaults("")
        assertEquals("false", values["MESA_SHADER_CACHE_DISABLE"])
        assertEquals("sysmem", values["TU_DEBUG"])
        assertEquals("12_2", values["VKD3D_FEATURE_LEVEL"])
        assertEquals("6_6", values["VKD3D_SHADER_MODEL"])
        assertEquals("mtrack", GameEnvironment.defaults("PERFORMANCE_TSO")["FEX_SMCCHECKS"])
    }

    @Test fun entriesAreTaggedWithWhereTheirValueComesFrom() {
        val config = GameEnvironment.Config(shared = mapOf("CUSTOM" to "shared", "TU_DEBUG" to "gmem"))
            .withEntries("42", mapOf("CUSTOM" to "game"))
        val origins = GameEnvironment.resolve(config, "", "42").associate { it.name to it.origin }
        assertEquals(GameEnvironment.Origin.PROFILE, origins["CUSTOM"])
        assertEquals(GameEnvironment.Origin.SHARED, origins["TU_DEBUG"])
        assertEquals(GameEnvironment.Origin.DEFAULT, origins["VKD3D_SHADER_MODEL"])
        assertEquals(GameEnvironment.Origin.PROFILE, GameEnvironment.resolve(config, "", "").first { it.name == "TU_DEBUG" }.origin)
        val names = GameEnvironment.resolve(config.withEntries("", mapOf("mesa_glthread" to "true", "A" to "1")), "", "").map { it.name }
        assertEquals(names.sortedWith(compareBy({ it.uppercase() }, { it })), names)
    }

    @Test fun gameOverridesSharedAndPresetWithoutLeakingToOtherGames() {
        val config = GameEnvironment.Config(
            shared = mapOf("FEX_MULTIBLOCK" to "0", "VKD3D_FEATURE_LEVEL" to "12_2"),
            games = mapOf("42" to mapOf("FEX_MULTIBLOCK" to "1", "VKD3D_FEATURE_LEVEL" to null)),
        )
        val game = values(config, "COMPATIBILITY", "42")
        assertEquals("1", game["FEX_MULTIBLOCK"])
        assertTrue(game.containsKey("VKD3D_FEATURE_LEVEL"))
        assertNull(game["VKD3D_FEATURE_LEVEL"])
        val other = values(config, "COMPATIBILITY", "43")
        assertEquals("0", other["FEX_MULTIBLOCK"])
        assertEquals("12_2", other["VKD3D_FEATURE_LEVEL"])
    }

    @Test fun graphicsDefaultsRespectExistingEditsAndRemovals() {
        val config = GameEnvironment.Config(shared = mapOf("VKD3D_FEATURE_LEVEL" to "12_0"))
            .withEntries("42", mapOf("VKD3D_SHADER_MODEL" to null))
        val game = values(config, "", "42")
        assertEquals("12_0", game["VKD3D_FEATURE_LEVEL"])
        assertTrue(game.containsKey("VKD3D_SHADER_MODEL"))
        assertNull(game["VKD3D_SHADER_MODEL"])
        val restored = values(config.withEntries("42", emptyMap()), "", "42")
        assertEquals("6_6", restored["VKD3D_SHADER_MODEL"])
    }

    @Test fun multiSelectionPreservesCustomTokensAndRemovesToggledValues() {
        assertEquals("fps,custom_token,frametimes", GameEnvironmentOptions.toggle("fps,custom_token", "frametimes"))
        assertEquals("custom_token", GameEnvironmentOptions.toggle("fps,custom_token", "fps"))
        assertEquals("", GameEnvironmentOptions.toggle("fps", "fps"))
        assertEquals("fps", GameEnvironmentOptions.toggle("", "fps"))
        assertEquals("enable_tp_ubwc_flag_hint=1:storage_8bit=1", GameEnvironmentOptions.toggle("enable_tp_ubwc_flag_hint=1", "storage_8bit=1", ':'))
    }

    @Test fun optionDefaultsFollowTheBuiltInDefaults() {
        for ((name, value) in GameEnvironment.defaults("")) GameEnvironmentOptions.find(name)?.let { assertEquals(name, value, it.value) }
        assertFalse(GameEnvironmentOptions.find("TU_DEBUG")!!.choices.contains("noconform"))
        assertEquals(listOf("auto", "lazy", "db"), GameEnvironmentOptions.find("ZINK_DESCRIPTORS")!!.choices)
        assertEquals(':', GameEnvironmentOptions.find("FD_DEV_FEATURES")!!.separator)
    }

    @Test fun switchingPresetDropsOldPresetOnlyVariables() {
        val config = GameEnvironment.Config()
        assertEquals("none", values(config, "EXTREME", "")["FEX_SMCCHECKS"])
        assertFalse(values(config, "COMPATIBILITY", "").containsKey("FEX_SMCCHECKS"))
        assertFalse(values(config, "", "").containsKey("FEX_MULTIBLOCK"))
    }

    @Test fun resettingOneProfilePreservesOtherProfiles() {
        val config = GameEnvironment.Config(shared = mapOf("CUSTOM" to "shared"))
            .withEntries("42", mapOf("CUSTOM" to "game"))
            .withEntries("43", mapOf("CUSTOM" to "other"))
            .withEntries("42", emptyMap())
        assertEquals("shared", values(config, "", "42")["CUSTOM"])
        assertEquals("other", values(config, "", "43")["CUSTOM"])
        assertFalse(config.games.containsKey("42"))
    }

    @Test fun validationPreservesLiteralValuesAndRejectsInvalidKeys() {
        assertTrue(GameEnvironment.validName("mesa_glthread"))
        assertFalse(GameEnvironment.validName("X=Y"))
        assertFalse(GameEnvironment.validName("1VAR"))
        assertFalse(GameEnvironment.validName("VAR\n"))
        assertTrue(GameEnvironment.validValue("a=b 'quote' $(touch file) ; $"))
        assertTrue(GameEnvironment.validValue(""))
        assertFalse(GameEnvironment.validValue("bad\u0000value"))
        assertFalse(GameEnvironment.validValue("x".repeat(8193)))
        assertTrue(GameEnvironment.validScope("4294967295"))
        for (scope in listOf("0", "-1", "01", "+1", "4294967296", "../42")) assertFalse(GameEnvironment.validScope(scope))
    }
}
