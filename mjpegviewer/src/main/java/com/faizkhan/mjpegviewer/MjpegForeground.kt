package com.faizkhan.mjpegviewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Rect
import android.os.AsyncTask
import android.os.Build
import android.os.Environment
import android.util.AttributeSet
import android.util.Log
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
class MjpegForeground{
    val MODE_ORIGINAL = 0
    val MODE_FIT_WIDTH = 1
    val MODE_FIT_HEIGHT = 2
    val MODE_BEST_FIT = 3
    val MODE_STRETCH = 4
    private val WAIT_AFTER_READ_IMAGE_ERROR_MSEC = 5000
    private val CHUNK_SIZE = 4096
    val tag = javaClass.simpleName
    val TAG = MjpegView::class.java.simpleName
    private val INTENT_REQUEST_CODE = 100
    var context1: Context? = null
    var url1: String? = null
    private var lastBitmap: Bitmap? = null
    var downloader: Downloader?=null
    private val lockBitmap = Any()
    private val paint: Paint? = null
    private val dst: Rect? = null
    private var mode1 = MODE_ORIGINAL
    private val drawX = 0
    private  var drawY:Int = 0
    private  var vWidth:Int = -1
    private  var vHeight:Int = -1
    private var lastImgWidth = 0
    private  var lastImgHeight:Int = 0
    private val adjustWidth = false
    private  var adjustHeight:kotlin.Boolean = false
    private val msecWaitAfterReadImageError = WAIT_AFTER_READ_IMAGE_ERROR_MSEC
     val isRecycleBitmap1 = false
    private val isUserForceConfigRecycle = false
    var image = ByteArray(0)
    var read = ByteArray(CHUNK_SIZE)
    lateinit var tmpCheckBoundry: ByteArray
    var readByte = 0
    var boundaryIndex:Int = 0
    var checkHeaderStr: String? = null
    var boundary:kotlin.String? = null
    var photo: File? = null
    constructor(context: Context) : super() {
        this.context1 = context
    }

    constructor(
        context: Context,
        attrs: AttributeSet?
    ) : super() {
        this.context1= context
    }

    fun setUrl(url: String?) {
        this.url1 = url
    }

    @Throws(IOException::class)
    fun startStream() {
        if (downloader != null && downloader!!.isRunning) {
            Log.w(tag, "Already started, stop by calling stopStream() first.")
            return
        }
        downloader = Downloader()
        downloader!!.start()
    }

    fun stopStream() {
        downloader!!.cancel()
    }

    fun getMode(): Int {
        return mode1
    }

    fun setMode(mode: Int) {
        this.mode1 = mode
        lastImgWidth = -1 // force re-calculate view size
    }

    inner class Downloader : Thread() {

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

        fun setBitmap(bm: Bitmap?) {
            Log.v(tag, "New frame")
            synchronized(lockBitmap) {
                if (lastBitmap != null && isUserForceConfigRecycle && isRecycleBitmap1) {
                    Log.v(tag, "Manually recycle bitmap")
                    lastBitmap!!.recycle()
                }
                lastBitmap = bm
            }

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
        private const val WAIT_AFTER_READ_IMAGE_ERROR_MSEC = 5000
        private const val CHUNK_SIZE = 4096
    }
}
