package com.faizkhan.mjpegviewer

import android.app.Activity
import android.content.Context
import android.graphics.*
import android.os.AsyncTask
import android.os.Build
import android.os.Environment
import android.util.AttributeSet
import android.util.Log
import android.view.View
import androidx.annotation.RequiresApi
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.regex.Matcher
import java.util.regex.Pattern


class MjpegView : View {
    val tag = javaClass.simpleName
    private var context1: Context
    var url1: String? = null
    private var lastBitmap: Bitmap? = null
    var foreground: MjpegDownloader? = null
    private val lockBitmap = Any()
    private var paint: Paint? = null
    private var dst: Rect? = null
    var mode1 = MODE_ORIGINAL
    private var drawX = 0
    private var drawY = 0
    private var vWidth = -1
    private var vHeight = -1
    private var lastImgWidth = 0
    private var lastImgHeight = 0
    var isAdjustWidth = false
    var isAdjustHeight = false
    var msecWaitAfterReadImageError =
        WAIT_AFTER_READ_IMAGE_ERROR_MSEC
    var isRecycleBitmap1 = false
    private var isUserForceConfigRecycle = false

    constructor(context: Context) : super(context) {
        this.context1 = context
        init()
    }

    constructor(
        context: Context,
        attrs: AttributeSet?
    ) : super(context, attrs) {
        this.context1= context
        init()
    }

    private fun init() {
        paint = Paint(Paint.ANTI_ALIAS_FLAG)
        dst = Rect(0, 0, 0, 0)
    }

    fun setUrl(url: String?) {
        this.url1 = url
    }

    @Throws(IOException::class)
    fun startStream() {
        if (foreground != null && foreground!!.isRunning) {
            Log.w(tag, "Already started, stop by calling stopStream() first.")
            return
        }
        foreground = MjpegDownloader()
        foreground!!.start()
    }

    fun stopStream() {
        foreground!!.cancel()
    }

    fun getMode(): Int {
        return mode1
    }

    fun setMode(mode: Int) {
        this.mode1 = mode
        lastImgWidth = -1 // force re-calculate view size
        requestLayout()
    }

    fun setBitmap(bm: Bitmap?) {
        Log.v(tag, "New frame")
        synchronized(lockBitmap) {
            if (lastBitmap != null && isUserForceConfigRecycle && isRecycleBitmap1) {
                Log.v(tag, "Manually recycle bitmap")
                lastBitmap!!.recycle()
            }
            lastBitmap = bm
        }
        if (context is Activity) {
            (context as Activity).runOnUiThread {
                invalidate()
                requestLayout()
            }
        } else {
            Log.e(
                tag,
                "Can not request Canvas's redraw. Context is not an instance of Activity"
            )
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var shouldRecalculateSize: Boolean
        synchronized(lockBitmap) {
            shouldRecalculateSize =
                lastBitmap != null && (lastImgWidth != lastBitmap!!.width || lastImgHeight != lastBitmap!!.height)
            if (shouldRecalculateSize) {
                lastImgWidth = lastBitmap!!.width
                lastImgHeight = lastBitmap!!.height
            }
        }
        if (shouldRecalculateSize) {
            Log.d(tag, "Recalculate view/image size")
            vWidth = MeasureSpec.getSize(widthMeasureSpec)
            vHeight = MeasureSpec.getSize(heightMeasureSpec)
            if (mode1 == MODE_ORIGINAL) {
                drawX = (vWidth - lastImgWidth) / 2
                drawY = (vHeight - lastImgHeight) / 2
                if (isAdjustWidth) {
                    vWidth = lastImgWidth
                    drawX = 0
                }
                if (isAdjustHeight) {
                    vHeight = lastImgHeight
                    drawY = 0
                }
            } else if (mode1 == MODE_FIT_WIDTH) {
                val newHeight =
                    (lastImgHeight.toFloat() / lastImgWidth.toFloat() * vWidth).toInt()
                drawX = 0
                if (isAdjustHeight) {
                    vHeight = newHeight
                    drawY = 0
                } else {
                    drawY = (vHeight - newHeight) / 2
                }

                //no need to check adjustWidth because in this mode image's width is always equals view's width.
                dst!![drawX, drawY, vWidth] = drawY + newHeight
            } else if (mode1 == MODE_FIT_HEIGHT) {
                val newWidth =
                    (lastImgWidth.toFloat() / lastImgHeight.toFloat() * vHeight).toInt()
                drawY = 0
                if (isAdjustWidth) {
                    vWidth = newWidth
                    drawX = 0
                } else {
                    drawX = (vWidth - newWidth) / 2
                }

                //no need to check adjustHeight because in this mode image's height is always equals view's height.
                dst!![drawX, drawY, drawX + newWidth] = vHeight
            } else if (mode1 == MODE_BEST_FIT) {
                if (lastImgWidth.toFloat() / vWidth.toFloat() > lastImgHeight.toFloat() / vHeight.toFloat()) {
                    //duplicated code
                    //fit width
                    val newHeight =
                        (lastImgHeight.toFloat() / lastImgWidth.toFloat() * vWidth) as Int
                    drawX = 0
                    if (isAdjustHeight) {
                        vHeight = newHeight
                        drawY = 0
                    } else {
                        drawY = (vHeight - newHeight) / 2
                    }

                    //no need to check adjustWidth because in this mode image's width is always equals view's width.
                    dst!![drawX, drawY, vWidth] = drawY + newHeight
                } else {
                    //duplicated code
                    //fit height
                    val newWidth =
                        (lastImgWidth.toFloat() / lastImgHeight.toFloat() * vHeight) as Int
                    drawY = 0
                    if (isAdjustWidth) {
                        vWidth = newWidth
                        drawX = 0
                    } else {
                        drawX = (vWidth - newWidth) / 2
                    }

                    //no need to check adjustHeight because in this mode image's height is always equals view's height.
                    dst!![drawX, drawY, drawX + newWidth] = vHeight
                }
            } else if (mode1 == MODE_STRETCH) {
                dst!![0, 0, vWidth] = vHeight
                //no need to check neither adjustHeight nor adjustHeight because in this mode image's size is always equals view's size.
            }
        } else {
            if (vWidth == -1 || vHeight == -1) {
                vWidth = MeasureSpec.getSize(widthMeasureSpec)
                vHeight = MeasureSpec.getSize(heightMeasureSpec)
            }
        }
        setMeasuredDimension(vWidth, vHeight)
    }

    override fun onDraw(c: Canvas) {
        synchronized(lockBitmap) {
            if (c != null && lastBitmap != null && !lastBitmap!!.isRecycled) {
                if (isInEditMode) {
                    // TODO: preview while edit xml
                } else if (mode1 != MODE_ORIGINAL) {
                    c.drawBitmap(lastBitmap!!, null, dst!!, paint)
                } else {
                    c.drawBitmap(lastBitmap!!, drawX.toFloat(), drawY.toFloat(), paint)
                }
            } else {
                Log.d(tag, "Skip drawing, canvas is null or bitmap is not ready yet")
            }
        }
    }

    fun isRecycleBitmap(): Boolean {
        return isRecycleBitmap1
    }

    fun setRecycleBitmap(recycleBitmap: Boolean) {
        isUserForceConfigRecycle = true
        isRecycleBitmap1 = recycleBitmap
    }

    inner class MjpegDownloader : Thread() {
//        private var prefManager: PrefManager? = null
        var image = ByteArray(0)
        var read = ByteArray(CHUNK_SIZE)
        lateinit var tmpCheckBoundry: ByteArray
        var readByte = 0
        var boundaryIndex = 0
        var checkHeaderStr: String? = null
        var boundary: String? = null
        var isRunning = true
            private set

        fun cancel() {
            isRunning = false
        }

        @RequiresApi(api = Build.VERSION_CODES.O) // Saving image
        fun saveImage(img: ByteArray) {
            val asyncTask: AsyncTask<Void?, String?, Void?> =
                object : AsyncTask<Void?, String?, Void?>() {
                    @RequiresApi(api = Build.VERSION_CODES.O)
                    override fun doInBackground(vararg params: Void?): Void? {
                        val ts = Instant.now().epochSecond
                        val dir = Environment.getExternalStorageDirectory()
                        val folder = File("$dir/Mjpeg/")
                        var success = true
                        if (!folder.exists()) {
                            success = folder.mkdirs()
                            println("Directory is created!" + success + "    " + folder.path)
                        }
                        val photo =
                            File(folder.path + "/photo_" + ts + "_" + (Math.random() * (100 + 1) + 1) + ".jpg")
                        if (photo.exists()) {
                            photo.delete()
                        }
                        try {
                            println("Photo $photo")
                            val fos =
                                FileOutputStream(photo.path)
                            println("Image_length" + img.size)
                            //img.compress(Bitmap.CompressFormat.PNG, 100, fos);
                            fos.write(img)
                            fos.close()
                            //sleepSecond();
                        } catch (e: IOException) {
                            Log.e("PictureDemo", "Exception in photoCallback", e)
                        }
                        return null
                    }

                    private fun sleepSecond() {
                        try {
                            TimeUnit.SECONDS.sleep(2)
                        } catch (ignore: InterruptedException) {
                        }
                    }
                }
            asyncTask.execute()
        }

        @RequiresApi(api = Build.VERSION_CODES.O)
        override fun run() {
            while (isRunning) {
                var connection: HttpURLConnection? = null
                var bis: BufferedInputStream? = null
                var serverUrl: URL? = null
                try {
                    serverUrl = URL(url1)
                    connection = serverUrl.openConnection() as HttpURLConnection
                    connection.doInput = true
                    connection!!.connect()
                    var headerBoundary =
                        "[_a-zA-Z0-9]*boundary" // Default boundary pattern
                    try {
                        // Try to extract a boundary from HTTP header first.
                        // If the information is not presented, throw an exception and use default value instead.
                        val contentType = connection.getHeaderField("Content-Type")
                            ?: throw Exception("Unable to get content type")
                        val types =
                            contentType.split(";".toRegex()).toTypedArray()
                        if (types.size == 0) {
                            throw Exception("Content type was empty")
                        }
                        var extractedBoundary: String? = null
                        for (ct in types) {
                            val trimmedCt = ct.trim { it <= ' ' }
                            if (trimmedCt.startsWith("boundary=")) {
                                extractedBoundary =
                                    trimmedCt.substring(9) // Content after 'boundary='
                            }
                        }
                        if (extractedBoundary == null) {
                            throw Exception("Unable to find mjpeg boundary")
                        }
                        headerBoundary = extractedBoundary
                    } catch (e: Exception) {
                        Log.w(
                            tag,
                            "Cannot extract a boundary string from HTTP header with message: " + e.message + ". Use a default value instead."
                        )
                    }
                    //determine boundary pattern
                    //use the whole header as separator in case boundary locate in difference chunks
                    val pattern = Pattern.compile(
                        "--$headerBoundary\\s+(.*)\\r\\n\\r\\n",
                        Pattern.DOTALL
                    )
                    var matcher: Matcher
                    bis = BufferedInputStream(connection.inputStream)
                    //always keep reading images from server
                    while (isRunning) {
                        try {
                            readByte = bis.read(read)
                            //no more data
                            if (readByte == -1) {
                                break
                            }
                            tmpCheckBoundry = addByte(image, read, 0, readByte)
                            checkHeaderStr = String(tmpCheckBoundry, StandardCharsets.US_ASCII)
                            matcher = pattern.matcher(checkHeaderStr)
                            if (matcher.find()) {
                                //boundary is found

                                boundary = matcher.group(0)
                                val boundary = boundary ?: return
                                boundaryIndex = checkHeaderStr!!.indexOf(boundary)
                                boundaryIndex -= image.size
                                image = if (boundaryIndex > 0) {
                                    addByte(image, read, 0, boundaryIndex)
                                } else {
                                    delByte(image, -boundaryIndex)
                                }
                                val outputImg =
                                    BitmapFactory.decodeByteArray(image, 0, image.size)
                                //final Bitmap outputImg1 = BitmapFactory.decodeByteArray(image, 0, image.length);
                                //Bitmap bmp2 = outputImg.copy(outputImg.getConfig(), true);
                                if (outputImg != null) {
                                    if (isRunning) {
                                        newFrame(outputImg)
                                        // Calling saveImage Function to save image
                                        saveImage(image)
                                    }
                                } else {
                                    Log.e(tag, "Read image error")
                                }
                                val headerIndex = boundaryIndex + boundary!!.length
                                image = addByte(
                                    ByteArray(0),
                                    read,
                                    headerIndex,
                                    readByte - headerIndex
                                )
                            } else {
                                image = addByte(image, read, 0, readByte)
                            }
                        } catch (e: Exception) {
                            if (e.message != null) {
                                Log.e(tag, e.message)
                            }
                            break
                        }
                    }
                } catch (e: Exception) {
                    if (e.message != null) {
                        Log.e(tag, e.message)
                    }
                }
                try {
                    assert(bis != null)
                    bis!!.close()
                    connection!!.disconnect()
                    Log.i(tag, "disconnected with $url1")
                } catch (e: Exception) {
                    if (e.message != null) {
                        Log.e(tag, e.message)
                    }
                }
                if (msecWaitAfterReadImageError > 0) {
                    try {
                        sleep(msecWaitAfterReadImageError.toLong())
                    } catch (e: InterruptedException) {
                        if (e.message != null) {
                            Log.e(tag, e.message)
                        }
                    }
                }
            }
        }

        private fun addByte(
            base: ByteArray,
            add: ByteArray,
            addIndex: Int,
            length: Int
        ): ByteArray {
            val tmp = ByteArray(base.size + length)
            System.arraycopy(base, 0, tmp, 0, base.size)
            System.arraycopy(add, addIndex, tmp, base.size, length)
            return tmp
        }

        private fun delByte(base: ByteArray, del: Int): ByteArray {
            val tmp = ByteArray(base.size - del)
            System.arraycopy(base, 0, tmp, 0, tmp.size)
            return tmp
        }

        private fun newFrame(bitmap: Bitmap) {
            setBitmap(bitmap)
        }
    }

    companion object {
        const val MODE_ORIGINAL = 0
        const val MODE_FIT_WIDTH = 1
        const val MODE_FIT_HEIGHT = 2
        const val MODE_BEST_FIT = 3
        const val MODE_STRETCH = 4
        const val WAIT_AFTER_READ_IMAGE_ERROR_MSEC = 5000
        private const val CHUNK_SIZE = 4096

    }
}
