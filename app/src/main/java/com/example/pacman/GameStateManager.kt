package com.example.pacman

import android.content.Context
import android.content.SharedPreferences

/**
 * Manager for saving and loading game state locally
 * Stores score, progress, high scores, and game settings
 */
class GameStateManager(context: Context) {
    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("pacman_game_state", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_HIGH_SCORE = "high_score"
        private const val KEY_LAST_SCORE = "last_score"
        private const val KEY_GAMES_PLAYED = "games_played"
        private const val KEY_TOTAL_DOTS_COLLECTED = "total_dots_collected"
        private const val KEY_USE_GYROSCOPE = "use_gyroscope"
        private const val KEY_USE_LIGHT_SENSOR = "use_light_sensor"
        private const val KEY_SELECTED_THEME = "selected_theme"
        private const val KEY_GAME_SPEED = "game_speed"
        private const val KEY_DIFFICULTY_LEVEL = "difficulty_level"
    }

    /**
     * Save the current score and update high score if necessary
     */
    fun saveScore(score: Int) {
        val highScore = getHighScore()
        if (score > highScore) {
            sharedPreferences.edit().putInt(KEY_HIGH_SCORE, score).apply()
        }
        sharedPreferences.edit().putInt(KEY_LAST_SCORE, score).apply()
        
        // Update statistics
        val gamesPlayed = getGamesPlayed()
        sharedPreferences.edit().putInt(KEY_GAMES_PLAYED, gamesPlayed + 1).apply()
        
        val totalDots = getTotalDotsCollected()
        sharedPreferences.edit().putInt(KEY_TOTAL_DOTS_COLLECTED, totalDots + score).apply()
    }

    /**
     * Get the high score
     */
    fun getHighScore(): Int {
        return sharedPreferences.getInt(KEY_HIGH_SCORE, 0)
    }

    /**
     * Get the last game score
     */
    fun getLastScore(): Int {
        return sharedPreferences.getInt(KEY_LAST_SCORE, 0)
    }

    /**
     * Get total number of games played
     */
    fun getGamesPlayed(): Int {
        return sharedPreferences.getInt(KEY_GAMES_PLAYED, 0)
    }

    /**
     * Get total dots collected across all games
     */
    fun getTotalDotsCollected(): Int {
        return sharedPreferences.getInt(KEY_TOTAL_DOTS_COLLECTED, 0)
    }

    /**
     * Save game settings (gyroscope, light sensor, theme)
     */
    fun saveSettings(useGyroscope: Boolean, useLightSensor: Boolean, selectedTheme: String) {
        sharedPreferences.edit().apply {
            putBoolean(KEY_USE_GYROSCOPE, useGyroscope)
            putBoolean(KEY_USE_LIGHT_SENSOR, useLightSensor)
            putString(KEY_SELECTED_THEME, selectedTheme)
            apply()
        }
    }

    /**
     * Load gyroscope setting
     */
    fun getUseGyroscope(): Boolean {
        return sharedPreferences.getBoolean(KEY_USE_GYROSCOPE, false)
    }

    /**
     * Load light sensor setting
     */
    fun getUseLightSensor(): Boolean {
        return sharedPreferences.getBoolean(KEY_USE_LIGHT_SENSOR, true)
    }

    /**
     * Load theme setting
     */
    fun getSelectedTheme(): String {
        return sharedPreferences.getString(KEY_SELECTED_THEME, "SYSTEM") ?: "SYSTEM"
    }

    /**
     * Save game speed (difficulty level affects ghost speed)
     */
    fun saveGameSpeed(speed: Int) {
        sharedPreferences.edit().putInt(KEY_GAME_SPEED, speed).apply()
    }

    /**
     * Get game speed
     */
    fun getGameSpeed(): Int {
        return sharedPreferences.getInt(KEY_GAME_SPEED, 200) // Default 200ms per move
    }

    /**
     * Save difficulty level
     */
    fun saveDifficultyLevel(level: Int) {
        sharedPreferences.edit().putInt(KEY_DIFFICULTY_LEVEL, level).apply()
    }

    /**
     * Get difficulty level (1-3: Easy, Normal, Hard)
     */
    fun getDifficultyLevel(): Int {
        return sharedPreferences.getInt(KEY_DIFFICULTY_LEVEL, 1) // Default: Easy
    }

    /**
     * Clear all saved data
     */
    fun clearAllData() {
        sharedPreferences.edit().clear().apply()
    }

    /**
     * Reset game statistics but keep high score
     */
    fun resetSessionData() {
        val highScore = getHighScore()
        sharedPreferences.edit().apply {
            putInt(KEY_LAST_SCORE, 0)
            putInt(KEY_HIGH_SCORE, highScore)
            apply()
        }
    }
}
