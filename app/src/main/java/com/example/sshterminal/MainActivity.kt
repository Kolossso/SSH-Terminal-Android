package com.example.sshterminal

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.Properties
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session

class MainActivity : AppCompatActivity() {
    private lateinit var outputText: TextView
    private lateinit var commandInput: EditText
    private lateinit var sendButton: Button
    private lateinit var connectButton: Button
    private lateinit var keyButton: Button
    private lateinit var scrollView: ScrollView
    private lateinit var shortcutButton1: Button
    private lateinit var shortcutButton2: Button
    private lateinit var shortcutButton3: Button
    
    private val handler = Handler(Looper.getMainLooper())
    private val commandHistory = mutableListOf<String>()
    private var historyIndex = -1
    
    private var session: Session? = null
    private var privateKeyPath: String? = null
    private val username = "zokirjonovjavohir61"
    private val host = "34.88.223.194"
    private val port = 22
    
    companion object {
        private const val READ_REQUEST_CODE = 42
        private const val PERMISSION_REQUEST_CODE = 123
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        outputText = findViewById(R.id.outputText)
        commandInput = findViewById(R.id.commandInput)
        sendButton = findViewById(R.id.sendButton)
        connectButton = findViewById(R.id.connectButton)
        keyButton = findViewById(R.id.keyButton)
        scrollView = findViewById(R.id.scrollView)
        shortcutButton1 = findViewById(R.id.shortcutButton1)
        shortcutButton2 = findViewById(R.id.shortcutButton2)
        shortcutButton3 = findViewById(R.id.shortcutButton3)
        
        // Проверка и запрос разрешений
        checkPermissions()
        
        keyButton.setOnClickListener {
            openFilePicker()
        }
        
        connectButton.setOnClickListener {
            if (privateKeyPath == null) {
                appendToOutput("Сначала выберите файл приватного ключа")
                return@setOnClickListener
            }
            
            connectButton.isEnabled = false
            appendToOutput("Подключение к $host:$port как $username...\n")
            
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    connectSSH()
                    withContext(Dispatchers.Main) {
                        connectButton.text = "Отключиться"
                        connectButton.isEnabled = true
                        sendButton.isEnabled = true
                        shortcutButton1.isEnabled = true
                        shortcutButton2.isEnabled = true
                        shortcutButton3.isEnabled = true
                        appendToOutput("Подключено успешно!\n")
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        appendToOutput("Ошибка подключения: ${e.message}\n")
                        connectButton.text = "Подключиться"
                        connectButton.isEnabled = true
                    }
                }
            }
        }
        
        sendButton.setOnClickListener {
            val command = commandInput.text.toString().trim()
            if (command.isEmpty()) return@setOnClickListener
            
            commandHistory.add(command)
            historyIndex = commandHistory.size
            commandInput.setText("")
            
            appendToOutput("> $command\n")
            
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val output = executeCommand(command)
                    withContext(Dispatchers.Main) {
                        appendToOutput(output)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        appendToOutput("Ошибка выполнения команды: ${e.message}\n")
                    }
                }
            }
        }
        
        // Настройка кнопок быстрого доступа
        shortcutButton1.setOnClickListener {
            val command = "cd /home/zokirjonovjavohir61/.steam/steam/steamapps/common/Counter-Strike\\ Global\\ Offensive/game/bin/linuxsteamrt64/ && ./start.sh"
            commandInput.setText(command)
        }
        
        shortcutButton2.setOnClickListener {
            val command = "steamcmd +login anonymous +app_update 730 validate +quit"
            commandInput.setText(command)
        }
        
        shortcutButton3.setOnClickListener {
            val command = "rcon_password your_rcon_password_here; rcon say Server controlled from Android!"
            commandInput.setText(command)
        }
        
        // Изначально отключаем кнопки до подключения
        sendButton.isEnabled = false
        shortcutButton1.isEnabled = false
        shortcutButton2.isEnabled = false
        shortcutButton3.isEnabled = false
    }
    
    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) 
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                PERMISSION_REQUEST_CODE
            )
        }
    }
    
    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, READ_REQUEST_CODE)
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == READ_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            data?.data?.also { uri ->
                privateKeyPath = copyUriToFile(uri)
                appendToOutput("Выбран ключ: $privateKeyPath\n")
                keyButton.text = "Ключ выбран"
                connectButton.isEnabled = true
            }
        }
    }
    
    private fun copyUriToFile(uri: Uri): String {
        val inputStream = contentResolver.openInputStream(uri)
        val tempFile = File(cacheDir, "id_rsa")
        
        FileOutputStream(tempFile).use { output ->
            inputStream?.copyTo(output)
        }
        
        // Установка правильных разрешений для ключа
        tempFile.setReadable(true, true)
        tempFile.setWritable(true, true)
        tempFile.setExecutable(false)
        
        return tempFile.absolutePath
    }
    
    private suspend fun connectSSH() {
        try {
            val jsch = JSch()
            jsch.addIdentity(privateKeyPath)
            
            session = jsch.getSession(username, host, port)
            
            val config = Properties()
            config["StrictHostKeyChecking"] = "no"
            session?.setConfig(config)
            
            session?.connect(30000)
        } catch (e: Exception) {
            throw Exception("Ошибка подключения: ${e.message}")
        }
    }
    
    private fun executeCommand(command: String): String {
        if (session == null || !session!!.isConnected) {
            return "Нет активного подключения\n"
        }
        
        val channel = session!!.openChannel("exec") as ChannelExec
        val outputBuffer = StringBuilder()
        
        try {
            channel.setCommand(command)
            
            channel.inputStream = null
            val inputStream = channel.inputStream
            val errorStream = channel.errStream
            
            channel.connect()
            
            // Чтение стандартного вывода
            val reader = BufferedReader(InputStreamReader(inputStream))
            val errorReader = BufferedReader(InputStreamReader(errorStream))
            
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                outputBuffer.append(line).append("\n")
                // Обновление UI в реальном времени
                val currentLine = line
                handler.post {
                    appendToOutput(currentLine + "\n")
                }
            }
            
            // Чтение потока ошибок
            while (errorReader.readLine().also { line = it } != null) {
                outputBuffer.append("ERROR: ").append(line).append("\n")
                val currentLine = line
                handler.post {
                    appendToOutput("ERROR: " + currentLine + "\n")
                }
            }
            
            while (!channel.isClosed) {
                Thread.sleep(100)
            }
            
            val exitStatus = channel.exitStatus
            if (exitStatus != 0) {
                outputBuffer.append("Код выхода: $exitStatus\n")
                handler.post {
                    appendToOutput("Код выхода: $exitStatus\n")
                }
            }
        } finally {
            channel.disconnect()
        }
        
        return outputBuffer.toString()
    }
    
    private fun appendToOutput(text: String) {
        outputText.append(text)
        scrollView.post {
            scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        session?.disconnect()
    }
}

