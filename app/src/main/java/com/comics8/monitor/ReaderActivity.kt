package com.comics8.monitor

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.comics8.monitor.data.ToonClient
import kotlin.concurrent.thread

class ReaderActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)

        val url = intent.getStringExtra(EXTRA_EPISODE_URL)
        if (url.isNullOrBlank()) {
            Toast.makeText(this, "URL is missing", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val container = findViewById<LinearLayout>(R.id.image_container)
        val progress = findViewById<ProgressBar>(R.id.loading_progress)

        val client = ToonClient()
        progress.visibility = ProgressBar.VISIBLE

        thread {
            try {
                val imageUrls = client.fetchImages(url)
                runOnUiThread {
                    progress.visibility = ProgressBar.GONE
                    for (imageUrl in imageUrls) {
                        val imageView = ImageView(this)
                        imageView.layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        imageView.adjustViewBounds = true
                        container.addView(imageView)

                        // Load each image asynchronously
                        thread {
                            try {
                                val bytes = client.fetchBytes(imageUrl)
                                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                runOnUiThread {
                                    imageView.setImageBitmap(bitmap)
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.visibility = ProgressBar.GONE
                    Toast.makeText(this, "Failed to load episode: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    companion object {
        const val EXTRA_EPISODE_URL = "episode_url"
    }
}
