@file:Suppress("MagicNumber", "TooManyFunctions", "SwallowedException")

package dev.benfranklin.tether.v6

import dev.benfranklin.tether.ui.tetherEdgeToEdge
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import dev.benfranklin.tether.core.features.CropRect
import dev.benfranklin.tether.core.proto.CaptureKinds
import dev.benfranklin.tether.log.Log
import dev.benfranklin.tether.runtime
import dev.benfranklin.tether.ui.Haptics
import dev.benfranklin.tether.ui.pressScale
import dev.benfranklin.tether.ui.theme.LocalReduceMotion
import dev.benfranklin.tether.ui.theme.Mono
import dev.benfranklin.tether.ui.theme.TetherTheme
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.abs

/**
 * Insert from Phone (0.5.2): the Mac asked for a photo or a document scan. Scans use ML Kit's
 * document scanner (Google Play services: edge detection, perspective fix, multi-page PDF); when
 * that isn't available, and for photos, the camera app takes the picture and Tether shows a crop
 * step. The result goes to the Mac over the fast path and is inserted where its user was typing.
 */
class CaptureActivity : ComponentActivity() {
    private lateinit var ref: String
    private lateinit var kind: String
    private var photoFile: File? = null
    private var finished = false
    private val step = mutableStateOf<Step>(Step.Intro)
    private val kindUi = mutableStateOf(CaptureKinds.PHOTO)

    private sealed interface Step {
        data object Intro : Step
        data class Crop(val bitmap: Bitmap) : Step
        data object Sending : Step
    }

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val result = if (r.resultCode == Activity.RESULT_OK) GmsDocumentScanningResult.fromActivityResultIntent(r.data) else null
        if (result == null) return@registerForActivityResult done(null, 0)
        val pages = result.pages.orEmpty()
        val src = if (pages.size > 1) result.pdf?.uri else pages.firstOrNull()?.imageUri
        val out = src?.let { copyToCache(it, if (pages.size > 1) "pdf" else "jpg") }
        step.value = Step.Sending
        done(out, maxOf(1, pages.size))
    }

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = photoFile
        if (!ok || f == null || !f.exists()) return@registerForActivityResult done(null, 0)
        val bmp = decode(f)
        if (bmp == null) done(null, 0, "error:decode") else step.value = Step.Crop(bmp)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        tetherEdgeToEdge()
        super.onCreate(savedInstanceState)
        ref = intent.getStringExtra(EXTRA_REF) ?: return finish()
        kind = intent.getStringExtra(EXTRA_KIND) ?: CaptureKinds.PHOTO
        kindUi.value = kind
        // Tells the phone side the launch got through (it falls back to a notification otherwise).
        applicationContext.runtime.v6.captureOpened(ref)
        setContent {
            TetherTheme {
                CaptureScreen(
                    kindUi.value, step.value as? Step.Crop != null, step.value is Step.Sending, (step.value as? Step.Crop)?.bitmap,
                    onShutter = ::start, onCancel = { done(null, 0) }, onUse = ::useCrop, onRetake = ::startCamera)
            }
        }
        if (savedInstanceState == null) window.decorView.postDelayed({ if (step.value is Step.Intro) start() }, 650)
    }

    /** singleTask: a new request while this screen is still up takes it over. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val newRef = intent.getStringExtra(EXTRA_REF) ?: return
        if (newRef == ref) return
        setIntent(intent)
        ref = newRef
        kind = intent.getStringExtra(EXTRA_KIND) ?: CaptureKinds.PHOTO
        kindUi.value = kind
        finished = false
        photoFile = null
        step.value = Step.Intro
        applicationContext.runtime.v6.captureOpened(ref)
        window.decorView.postDelayed({ if (step.value is Step.Intro) start() }, 650)
    }

    private fun start() {
        if (kind == CaptureKinds.SCAN && playServices()) startScanner() else startCamera()
    }

    private fun playServices(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS

    private fun startScanner() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(false)
            .setPageLimit(20)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG, GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(this)
            .addOnSuccessListener { sender -> scanLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener { e ->
                Log.w("capture.scanner_unavailable", "reason" to e.javaClass.simpleName)
                startCamera()
            }
    }

    private fun startCamera() {
        val dir = File(cacheDir, "captures").apply { mkdirs() }
        val f = File(dir, "shot-${System.currentTimeMillis()}.jpg")
        photoFile = f
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        try {
            cameraLauncher.launch(uri)
        } catch (e: android.content.ActivityNotFoundException) {
            done(null, 0, dev.benfranklin.tether.core.proto.CaptureReasons.NO_CAMERA)
        }
    }

    private fun useCrop(rect: CropRect) {
        val bmp = (step.value as? Step.Crop)?.bitmap ?: return
        step.value = Step.Sending
        Thread {
            val px = rect.pixels(bmp.width, bmp.height)
            val cropped = Bitmap.createBitmap(bmp, px[0], px[1], px[2], px[3])
            val out = File(File(cacheDir, "captures").apply { mkdirs() }, "out-${System.currentTimeMillis()}.jpg")
            try {
                FileOutputStream(out).use { cropped.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                photoFile?.delete()
                runOnUiThread { done(out, 1) }
            } catch (e: IOException) {
                runOnUiThread { done(null, 0, "error:write") }
            }
        }.start()
    }

    private fun decode(f: File): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder applies the EXIF rotation; cap the long side at 3000 px.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { d, info, _ ->
                val long = maxOf(info.size.width, info.size.height)
                if (long > 3000) d.setTargetSampleSize((long + 2999) / 3000)
                d.isMutableRequired = false
            }
        } else {
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 2 })
        }
    } catch (e: IOException) {
        null
    }

    private fun copyToCache(uri: Uri, ext: String): File? = try {
        val out = File(File(cacheDir, "captures").apply { mkdirs() }, "scan-${System.currentTimeMillis()}.$ext")
        contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(out).use { input.copyTo(it) } }
        out
    } catch (e: IOException) {
        null
    }

    private fun done(file: File?, pages: Int, error: String? = null) {
        if (finished) return
        finished = true
        applicationContext.runtime.v6.captureDone(ref, kind, file, pages, error)
        window.decorView.postDelayed({ finish() }, if (file != null) 650 else 0)
    }

    override fun onDestroy() {
        if (!finished && isFinishing) applicationContext.runtime.v6.captureDone(ref, kind, null, 0)
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_REF = "ref"
        private const val EXTRA_KIND = "kind"

        fun intent(c: Context, ref: String, kind: String): Intent = Intent(c, CaptureActivity::class.java)
            .putExtra(EXTRA_REF, ref).putExtra(EXTRA_KIND, kind)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}

/** Intro (pulsing shutter), crop step, then a check mark while it goes to the Mac. */
@Composable
private fun CaptureScreen(
    kind: String,
    cropping: Boolean,
    sending: Boolean,
    bitmap: Bitmap?,
    onShutter: () -> Unit,
    onCancel: () -> Unit,
    onUse: (CropRect) -> Unit,
    onRetake: () -> Unit,
) {
    val reduce = LocalReduceMotion.current
    val target = when {
        sending -> 2
        cropping && bitmap != null -> 1
        else -> 0
    }
    AnimatedContent(
        targetState = target,
        transitionSpec = {
            if (reduce) {
                fadeIn(tween(180)) togetherWith fadeOut(tween(180))
            } else {
                val enter = fadeIn(tween(260)) +
                    slideInVertically(tween(380, easing = FastOutSlowInEasing)) { it / 6 } + scaleIn(initialScale = 0.96f)
                enter.togetherWith(fadeOut(tween(160)))
            }
        },
        modifier = Modifier.fillMaxSize().background(Mono.Bg).safeDrawingPadding(),
        label = "capture",
    ) { s ->
        when (s) {
            0 -> Intro(kind, onShutter, onCancel)
            1 -> if (bitmap != null) CropStep(bitmap, kind, onUse, onRetake, onCancel)
            else -> SendingStep()
        }
    }
}

@Composable
private fun Intro(kind: String, onShutter: () -> Unit, onCancel: () -> Unit) {
    val reduce = LocalReduceMotion.current
    val view = LocalView.current
    val t = rememberInfiniteTransition(label = "shutter")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "p")
    val source = remember { MutableInteractionSource() }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(if (kind == CaptureKinds.SCAN) "SCAN DOCUMENT" else "TAKE PHOTO", color = Mono.Muted, fontSize = 12.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            if (kind == CaptureKinds.SCAN) "Your Mac wants a scan" else "Your Mac wants a photo",
            color = Mono.Text, fontSize = 22.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(48.dp))
        Box(Modifier.size(180.dp), contentAlignment = Alignment.Center) {
            if (!reduce) {
                Canvas(Modifier.fillMaxSize()) {
                    for (i in 0..1) {
                        val p = (pulse + i * 0.5f) % 1f
                        drawCircle(Mono.Text.copy(alpha = (1 - p) * 0.5f), radius = size.minDimension / 4 * (1 + p), style = Stroke(2f))
                    }
                }
            }
            Box(
                Modifier.size(92.dp).pressScale(source).clip(CircleShape).border(4.dp, Mono.Text, CircleShape).padding(8.dp)
                    .clip(CircleShape).background(Mono.Accent)
                    .clickable(source, null) {
                        Haptics.confirm(view)
                        onShutter()
                    },
            )
        }
        Spacer(Modifier.height(48.dp))
        Text("Cancel", color = Mono.Muted, fontSize = 14.sp, modifier = Modifier.clickable(onClick = onCancel).padding(12.dp))
    }
}

@Composable
private fun CropStep(bitmap: Bitmap, kind: String, onUse: (CropRect) -> Unit, onRetake: () -> Unit, onCancel: () -> Unit) {
    var rect by remember { mutableStateOf(if (kind == CaptureKinds.SCAN) CropRect.INSET else CropRect.FULL) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val view = LocalView.current
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Crop", color = Mono.Text, fontSize = 17.sp, modifier = Modifier.padding(8.dp))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val aspect = bitmap.width.toFloat() / bitmap.height
            val boxW = constraints.maxWidth.toFloat()
            val boxH = constraints.maxHeight.toFloat()
            val w = if (boxW / boxH > aspect) boxH * aspect else boxW
            val h = w / aspect
            val density = androidx.compose.ui.platform.LocalDensity.current
            Box(Modifier.size(with(density) { w.toDp() }, with(density) { h.toDp() })) {
                Image(image, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                Canvas(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        var handle = CropRect.Handle.MOVE
                        detectDragGestures(
                            onDragStart = { o ->
                                val x = o.x / size.width
                                val y = o.y / size.height
                                val near = 0.12f
                                handle = when {
                                    abs(x - rect.left) < near && abs(y - rect.top) < near -> CropRect.Handle.TOP_LEFT
                                    abs(x - rect.right) < near && abs(y - rect.top) < near -> CropRect.Handle.TOP_RIGHT
                                    abs(x - rect.left) < near && abs(y - rect.bottom) < near -> CropRect.Handle.BOTTOM_LEFT
                                    abs(x - rect.right) < near && abs(y - rect.bottom) < near ->
                                        CropRect.Handle.BOTTOM_RIGHT
                                    else -> CropRect.Handle.MOVE
                                }
                                Haptics.tick(view)
                            },
                            onDrag = { change, d ->
                                change.consume()
                                rect = rect.drag(handle, d.x / size.width, d.y / size.height)
                            },
                        )
                    },
                ) {
                    val l = rect.left * size.width
                    val t = rect.top * size.height
                    val r = rect.right * size.width
                    val b = rect.bottom * size.height
                    val shade = Color.Black.copy(alpha = 0.55f)
                    drawRect(shade, Offset.Zero, Size(size.width, t))
                    drawRect(shade, Offset(0f, b), Size(size.width, size.height - b))
                    drawRect(shade, Offset(0f, t), Size(l, b - t))
                    drawRect(shade, Offset(r, t), Size(size.width - r, b - t))
                    drawRect(Color.White, Offset(l, t), Size(r - l, b - t), style = Stroke(2.dp.toPx()))
                    for (c in listOf(Offset(l, t), Offset(r, t), Offset(l, b), Offset(r, b))) drawCircle(Color.White, 7.dp.toPx(), c)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Cancel", color = Mono.Muted, fontSize = 14.sp, modifier = Modifier.clickable(onClick = onCancel).padding(12.dp))
            Text("Retake", color = Mono.Text, fontSize = 14.sp, modifier = Modifier.clickable(onClick = onRetake).padding(12.dp))
            val source = remember { MutableInteractionSource() }
            Text(
                "Send to Mac", color = Mono.Bg, fontSize = 14.sp,
                modifier = Modifier.pressScale(source).clip(RoundedCornerShape(12.dp)).background(Mono.Accent)
                    .clickable(source, null) {
                        Haptics.confirm(view)
                        onUse(rect)
                    }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun SendingStep() {
    val reduce = LocalReduceMotion.current
    val t = rememberInfiniteTransition(label = "send")
    val s by t.animateFloat(0.92f, 1.04f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "s")
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(
            Modifier.size(84.dp).graphicsLayer {
                scaleX = if (reduce) 1f else s
                scaleY = if (reduce) 1f else s
            }.clip(CircleShape).background(Mono.Accent),
            contentAlignment = Alignment.Center,
        ) {
            Text("✓", color = Mono.Bg, fontSize = 38.sp)
        }
        Spacer(Modifier.height(20.dp))
        Text("Sending to your Mac", color = Mono.Text, fontSize = 15.sp)
    }
}
