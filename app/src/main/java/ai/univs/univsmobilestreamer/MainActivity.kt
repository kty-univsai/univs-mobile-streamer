package ai.univs.univsmobilestreamer

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private val backendUrlKey = ""
    private val REQUEST_CODE_PERMISSIONS = 1001
    private val REQUIRED_PERMISSIONS = arrayOf(
        android.Manifest.permission.CAMERA
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (allPermissionsGranted()) {
            startStreamingService()
        } else {
            requestPermissions(REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }


        val reconnectButton = findViewById<Button>(R.id.btn_reconnect)
        reconnectButton.setOnClickListener {
            val stopIntent = Intent(this, StreamingService::class.java)
            stopService(stopIntent)  // 현재 실행 중인 서비스 종료

            Handler(Looper.getMainLooper()).postDelayed({
                val startIntent = Intent(this, StreamingService::class.java)
                ContextCompat.startForegroundService(this, startIntent)
            }, 500) // 약
        }

        val qrButton = findViewById<Button>(R.id.btn_qr)
        qrButton.setOnClickListener {
            val intent = Intent(this, QRScanActivity::class.java)
            qrCodeResultLauncher.launch(intent)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startStreamingService()
            } else {
                finish() // 권한 없으면 앱 종료
            }
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun startStreamingService() {
        val intent = Intent(this, StreamingService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }


    private val qrCodeResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val scannedUrl = data?.getStringExtra("scanned_url")
        if (!scannedUrl.isNullOrEmpty()) {
            saveBackendUrl(scannedUrl)
            Toast.makeText(this, "URL 저장됨: $scannedUrl", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveBackendUrl(url: String) {
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        prefs.edit().putString(backendUrlKey, url).apply()
    }
}
