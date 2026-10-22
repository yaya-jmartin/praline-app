package com.yaya.praline

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Google ML Kit's on-device text recognition (1.4). Only the GitHub build has it: Google's servers aren't
 * reachable from the hand-made build, so [Ocr] looks this class up by name and offers the import only when
 * it is there. The Latin model ships inside the app, so nothing is downloaded and no picture leaves the phone.
 */
class MlKitReader : TextReader {
    override fun read(c: Context, b: Bitmap, done: (String?) -> Unit) {
        val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        client.process(InputImage.fromBitmap(b, 0))
            .addOnSuccessListener { t ->
                // block by block, top to bottom, so a two-column page reads one column at a time
                val blocks = t.textBlocks.sortedWith(compareBy({ (it.boundingBox?.left ?: 0) > b.width / 2 }, { it.boundingBox?.top ?: 0 }))
                done(blocks.joinToString("\n\n") { it.text })
                client.close()
            }
            .addOnFailureListener { done(null); client.close() }
    }
}
