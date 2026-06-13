package com.questloop.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.questloop.app.ui.QuestLoopApp
import com.questloop.app.ui.theme.QuestLoopTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as QuestLoopApplication).container
        setContent {
            QuestLoopTheme {
                QuestLoopApp(repository = container.repository)
            }
        }
    }
}
