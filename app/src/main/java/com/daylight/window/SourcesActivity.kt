package com.daylight.window

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Every number the app uses that came from outside it, with a link to the source.
 *
 * This exists so the advice can be checked rather than trusted. Anyone who wants to
 * know where "your limit" comes from can read the papers themselves.
 */
class SourcesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sources)

        val list = findViewById<LinearLayout>(R.id.sources_list)
        for (source in Evidence.ALL) {
            val card = layoutInflater.inflate(R.layout.row_source, list, false)
            card.findViewById<TextView>(R.id.source_title).text = source.title
            card.findViewById<TextView>(R.id.source_detail).text = source.detail
            card.findViewById<Button>(R.id.source_link).setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source.url)))
            }
            list.addView(card)
        }

        findViewById<Button>(R.id.done).setOnClickListener { finish() }
    }
}
