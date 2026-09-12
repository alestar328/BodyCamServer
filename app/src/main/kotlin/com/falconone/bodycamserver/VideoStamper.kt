package com.falconone.bodycamserver

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

private const val TAG = "FalconStamper"
private const val TIMEOUT_US = 10_000L

/**
 * Hace el **proxy** de un incidente: la copia ligera para el backend, a 720 de
 * lado corto y 15 fps, **con el rótulo del oficial quemado en los frames**.
 *
 * El firmware de esta unidad no trae watermark (verificado: ni el HAL de
 * Spreadtrum ni las apps del fabricante lo soportan), así que la única vía es
 * re-encodar: cada frame se decodifica, se le dibuja el rótulo encima por
 * OpenGL y se vuelve a codificar. Decodificador y encoder H.264 son hardware,
 * así que esto corre aprox. a la velocidad del vídeo. El audio no se toca: se
 * copian sus muestras tal cual.
 *
 * Por re-encodar, esto ya no se aplica al original (hasta el 2026-09-11 sí, y la
 * evidencia sellada era una segunda generación): solo a su copia. El escalado a
 * 720 sale gratis del mismo dibujo por OpenGL, que pinta el frame al tamaño de
 * salida, y la bajada a 15 fps es no pasar al encoder la mitad de los frames.
 *
 * El pipeline (patrón decode-edit-encode estándar de Android):
 *
 *     MediaExtractor ─► decoder ─► SurfaceTexture (textura externa GL)
 *                                        │  frame + rótulo (2 quads)
 *                                        ▼
 *                       encoder ◄─ InputSurface (EGL) ─► MediaMuxer
 *
 * Si cualquier cosa falla, el llamante conserva los segmentos: la evidencia
 * nunca depende de que esto salga bien.
 */
object VideoStamper {

    const val PROXY_SHORT_SIDE = 720
    const val PROXY_FPS = 15
    private const val PROXY_BITRATE = 1_500_000

    /**
     * @param source el original ya ensamblado.
     * @param out fichero del proxy (se crea aquí).
     * @return true si el proxy quedó completo en [out].
     */
    fun makeProxy(
        source: File,
        out: File,
        officer: Officer,
        rotationDegrees: Int = 0,
    ): Boolean {
        val segments = listOf(source)

        // Formatos de referencia: del primer segmento salen tamaño y pistas.
        val probe = MediaExtractor()
        var videoFormat: MediaFormat? = null
        var audioFormat: MediaFormat? = null
        try {
            probe.setDataSource(segments.first().absolutePath)
            for (i in 0 until probe.trackCount) {
                val f = probe.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoFormat == null) videoFormat = f
                if (mime.startsWith("audio/") && audioFormat == null) audioFormat = f
            }
        } finally {
            probe.release()
        }
        val vFormat = videoFormat ?: run { Log.e(TAG, "sin pista de vídeo"); return false }
        val (width, height) = proxySize(
            vFormat.getInteger(MediaFormat.KEY_WIDTH),
            vFormat.getInteger(MediaFormat.KEY_HEIGHT),
        )

        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var gl: StampSurface? = null
        try {
            val outFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, PROXY_BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, PROXY_FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            gl = StampSurface(encoder.createInputSurface(), width, height, officer)
            encoder.start()

            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            // La rotación del original viaja en metadatos; sin copiarla el vídeo
            // final saldría tumbado en los reproductores.
            muxer.setOrientationHint(rotationDegrees)

            val state = MuxState(muxer, audioFormat)
            val dropper = FrameDropper(PROXY_FPS)
            var offsetUs = 0L
            for (segment in segments) {
                offsetUs = transcodeSegment(segment, encoder, gl, state, dropper, offsetUs)
            }

            // Fin: EOS al encoder y drenar lo que quede.
            encoder.signalEndOfInputStream()
            drainEncoder(encoder, state, untilEos = true)

            muxer.stop()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "stamp falló: ${e.message}")
            return false
        } finally {
            try { gl?.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    /** Estado del muxer: no arranca hasta conocer el formato real del encoder. */
    private class MuxState(val muxer: MediaMuxer, val audioFormat: MediaFormat?) {
        var videoTrack = -1
        var audioTrack = -1
        var started = false

        /** Audio llegado antes de que el muxer arranque; se vuelca al arrancar. */
        val pendingAudio = ArrayList<Pair<ByteArray, MediaCodec.BufferInfo>>()

        fun onEncoderFormat(format: MediaFormat) {
            videoTrack = muxer.addTrack(format)
            if (audioFormat != null) audioTrack = muxer.addTrack(audioFormat)
            muxer.start()
            started = true
            pendingAudio.forEach { (bytes, info) ->
                muxer.writeSampleData(audioTrack, ByteBuffer.wrap(bytes), info)
            }
            pendingAudio.clear()
        }

        fun writeAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            if (started) {
                muxer.writeSampleData(audioTrack, buffer, info)
            } else {
                // Solo pasa durante los primeros ms, hasta el primer output del
                // encoder: el volumen retenido es mínimo.
                val bytes = ByteArray(info.size)
                buffer.get(bytes)
                val copy = MediaCodec.BufferInfo().apply {
                    set(0, info.size, info.presentationTimeUs, info.flags)
                }
                pendingAudio.add(bytes to copy)
            }
        }
    }

    /**
     * Transcodifica un segmento: vídeo por el pipeline GL, audio copiado.
     * Devuelve el offset acumulado para el siguiente segmento.
     */
    private fun transcodeSegment(
        segment: File,
        encoder: MediaCodec,
        gl: StampSurface,
        state: MuxState,
        dropper: FrameDropper,
        offsetUs: Long,
    ): Long {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var segmentEndUs = 0L
        try {
            extractor.setDataSource(segment.absolutePath)
            var videoIdx = -1
            var audioIdx = -1
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoIdx < 0) {
                    videoIdx = i
                    if (f.containsKey(MediaFormat.KEY_DURATION))
                        segmentEndUs = maxOf(segmentEndUs, f.getLong(MediaFormat.KEY_DURATION))
                }
                if (mime.startsWith("audio/") && audioIdx < 0) {
                    audioIdx = i
                    if (f.containsKey(MediaFormat.KEY_DURATION))
                        segmentEndUs = maxOf(segmentEndUs, f.getLong(MediaFormat.KEY_DURATION))
                }
            }
            if (videoIdx < 0) return offsetUs
            extractor.selectTrack(videoIdx)
            if (audioIdx >= 0) extractor.selectTrack(audioIdx)

            val vf = extractor.getTrackFormat(videoIdx)
            decoder = MediaCodec.createDecoderByType(vf.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(vf, gl.decoderSurface, null, 0)
            decoder.start()

            val audioBuf = ByteBuffer.allocateDirect(1 shl 19)
            val audioInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                // 1) alimentar: la muestra que toque según el orden del fichero
                if (!inputDone) {
                    when (extractor.sampleTrackIndex) {
                        audioIdx -> {
                            val size = extractor.readSampleData(audioBuf, 0)
                            if (size >= 0) {
                                audioInfo.set(0, size, extractor.sampleTime + offsetUs, 0)
                                state.writeAudio(audioBuf, audioInfo)
                                segmentEndUs = maxOf(segmentEndUs, extractor.sampleTime)
                            }
                            extractor.advance()
                        }
                        videoIdx -> {
                            val inIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                            if (inIdx >= 0) {
                                val buf = decoder.getInputBuffer(inIdx)!!
                                val size = extractor.readSampleData(buf, 0)
                                if (size >= 0) {
                                    decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                    segmentEndUs = maxOf(segmentEndUs, extractor.sampleTime)
                                    extractor.advance()
                                } else {
                                    decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                }
                            }
                        }
                        else -> {
                            // -1 = fin del fichero: EOS al decoder para drenarlo
                            val inIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                            if (inIdx >= 0) {
                                decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            }
                        }
                    }
                }

                // 2) frames decodificados → GL (rótulo) → encoder
                val info = MediaCodec.BufferInfo()
                val outIdx = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIdx >= 0) {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val ptsUs = info.presentationTimeUs + offsetUs
                    // Un frame descartado ni se pinta ni llega al encoder: así se
                    // baja de 30 a 15 fps sin tocar las marcas de los que quedan.
                    val render = info.size > 0 && dropper.keep(ptsUs)
                    decoder.releaseOutputBuffer(outIdx, render)
                    if (render) gl.awaitFrameAndStamp(ptsUs * 1000)
                    if (eos) outputDone = true
                }

                // 3) vaciar el encoder para que nunca se atasque
                drainEncoder(encoder, state, untilEos = false)
            }
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            extractor.release()
        }
        // El siguiente segmento continúa tras la duración real de este.
        return offsetUs + segmentEndUs
    }

    /**
     * Lado corto a [PROXY_SHORT_SIDE], sin agrandar nunca, y el largo en proporción
     * redondeado a múltiplo de 16, que es lo que el encoder de este SoC traga sin
     * rellenar. 1920x1080 da 1280x720; el 1920x1088 de la W1 da 1264x720.
     */
    fun proxySize(width: Int, height: Int): Pair<Int, Int> {
        val shortSide = minOf(width, height)
        if (shortSide <= PROXY_SHORT_SIDE) return width to height
        val longSide = maxOf(width, height).toLong() * PROXY_SHORT_SIDE / shortSide
        val longAligned = ((longSide + 8) / 16 * 16).toInt()
        return if (width >= height) longAligned to PROXY_SHORT_SIDE else PROXY_SHORT_SIDE to longAligned
    }

    private fun drainEncoder(encoder: MediaCodec, state: MuxState, untilEos: Boolean) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = encoder.dequeueOutputBuffer(info, if (untilEos) TIMEOUT_US else 0L)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> state.onEncoderFormat(encoder.outputFormat)
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEos) return
                idx >= 0 -> {
                    val buf = encoder.getOutputBuffer(idx)!!
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        state.muxer.writeSampleData(state.videoTrack, buf, info)
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    encoder.releaseOutputBuffer(idx, false)
                    if (eos) return
                }
                else -> if (!untilEos) return
            }
        }
    }
}

/**
 * Deja pasar [fps] frames por segundo siguiendo un calendario fijo: se queda el
 * primer frame que llega a cada cita, y la siguiente cita se fija desde la
 * anterior, no desde el frame guardado.
 *
 * Contarlos desde el último guardado fallaba con la W1, que a 1080p entrega unos
 * 24 fps y no 30 (medido el 2026-09-11): con citas "a 50 ms del último" se
 * quedaba justo uno de cada dos y el proxy salía a 12 fps. Con calendario la media
 * es la pedida venga la cámara a la frecuencia que venga.
 */
private class FrameDropper(fps: Int) {
    private val intervalUs = 1_000_000L / fps
    private var nextUs = Long.MIN_VALUE

    fun keep(ptsUs: Long): Boolean {
        if (ptsUs < nextUs) return false
        // Tras un hueco (el primer frame, o un corte largo) el calendario se
        // reinicia; si no, habría una ráfaga de frames seguidos "recuperando" citas.
        val calendarioPerdido = nextUs == Long.MIN_VALUE || ptsUs - nextUs > intervalUs
        nextUs = if (calendarioPerdido) ptsUs + intervalUs else nextUs + intervalUs
        return true
    }
}

/**
 * Superficie GL sobre el input del encoder, con el frame de cámara como
 * textura externa y el rótulo como textura normal encima.
 */
private class StampSurface(
    private val encoderSurface: Surface,
    private val width: Int,
    private val height: Int,
    officer: Officer,
) {
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private val frameLock = Object()
    private var frameAvailable = false

    private var program = 0
    private var programOes = 0
    private var frameTexture = 0
    private var labelTexture = 0
    private var labelWidth = 0
    private var labelHeight = 0

    private lateinit var surfaceTexture: SurfaceTexture
    val decoderSurface: Surface
    private val stMatrix = FloatArray(16)

    init {
        setupEgl()
        setupGl(officer)
        surfaceTexture = SurfaceTexture(frameTexture).apply {
            // El listener corre en el hilo que tenga Looper; este hilo no lo
            // tiene, así que Android usa el main — vale: solo toca el flag.
            setOnFrameAvailableListener {
                synchronized(frameLock) {
                    frameAvailable = true
                    frameLock.notifyAll()
                }
            }
        }
        decoderSurface = Surface(surfaceTexture)
    }

    /** Espera el frame recién liberado por el decoder, lo dibuja con el rótulo
     *  y lo entrega al encoder con su marca de tiempo. */
    fun awaitFrameAndStamp(presentationTimeNs: Long) {
        synchronized(frameLock) {
            val deadline = System.currentTimeMillis() + 2_000
            while (!frameAvailable) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) throw RuntimeException("frame del decoder no llegó")
                frameLock.wait(left)
            }
            frameAvailable = false
        }
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(stMatrix)

        GLES20.glViewport(0, 0, width, height)
        drawFrame()
        drawLabel()

        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentationTimeNs)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    // ── EGL ───────────────────────────────────────────────────────────────────

    private fun setupEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            0x3142 /* EGL_RECORDABLE_ANDROID */, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0)
        eglContext = EGL14.eglCreateContext(
            eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, configs[0], encoderSurface, intArrayOf(EGL14.EGL_NONE), 0
        )
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    // ── GL ────────────────────────────────────────────────────────────────────

    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            // x, y, u, v — pantalla completa
            put(floatArrayOf(
                -1f, -1f, 0f, 0f,
                 1f, -1f, 1f, 0f,
                -1f,  1f, 0f, 1f,
                 1f,  1f, 1f, 1f,
            ))
            position(0)
        }

    private fun setupGl(officer: Officer) {
        val vertex = """
            attribute vec4 aPos; attribute vec2 aTex; varying vec2 vTex;
            uniform mat4 uSt;
            void main() { gl_Position = vec4(aPos.xy, 0.0, 1.0); vTex = (uSt * vec4(aTex, 0.0, 1.0)).xy; }
        """
        val fragOes = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float; varying vec2 vTex; uniform samplerExternalOES uTexO;
            void main() { gl_FragColor = texture2D(uTexO, vTex); }
        """
        val frag2d = """
            precision mediump float; varying vec2 vTex; uniform sampler2D uTex;
            void main() { gl_FragColor = texture2D(uTex, vTex); }
        """
        programOes = buildProgram(vertex, fragOes)
        program = buildProgram(vertex, frag2d)

        val textures = IntArray(2)
        GLES20.glGenTextures(2, textures, 0)
        frameTexture = textures[0]
        labelTexture = textures[1]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, frameTexture)
        texParams(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)

        val label = renderLabel(officer)
        labelWidth = label.width
        labelHeight = label.height
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, labelTexture)
        texParams(GLES20.GL_TEXTURE_2D)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, label, 0)
        label.recycle()
    }

    /** El rótulo se pinta UNA vez a bitmap; cada frame solo dibuja el quad. */
    private fun renderLabel(officer: Officer): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = height / 24f          // ~30 px en 720p, legible sin tapar
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        val lines = listOf(
            "Officer: ${officer.name}",
            "Rank: ${officer.rank}   Badge: ${officer.badge}",
        )
        val lineH = paint.fontSpacing
        val w = lines.maxOf { paint.measureText(it) }.toInt() + 24
        val h = (lineH * lines.size).toInt() + 16
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        lines.forEachIndexed { i, line ->
            canvas.drawText(line, 12f, lineH * (i + 1) - paint.descent() + 4f, paint)
        }
        return bmp
    }

    private fun drawFrame() {
        GLES20.glUseProgram(programOes)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, frameTexture)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(programOes, "uSt"), 1, false, stMatrix, 0)
        drawQuad(programOes, quad)
    }

    private fun drawLabel() {
        // Esquina inferior izquierda, con margen. En coordenadas NDC.
        val margin = 0.03f
        val w = 2f * labelWidth / width
        val h = 2f * labelHeight / height
        val x0 = -1f + margin
        val y0 = -1f + margin
        val buf = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        // La textura del bitmap tiene el origen arriba: se invierte la V.
        buf.put(floatArrayOf(
            x0,     y0,     0f, 1f,
            x0 + w, y0,     1f, 1f,
            x0,     y0 + h, 0f, 0f,
            x0 + w, y0 + h, 1f, 0f,
        ))
        buf.position(0)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, labelTexture)
        val identity = FloatArray(16)
        android.opengl.Matrix.setIdentityM(identity, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uSt"), 1, false, identity, 0)
        drawQuad(program, buf)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun drawQuad(prog: Int, vertices: FloatBuffer) {
        val aPos = GLES20.glGetAttribLocation(prog, "aPos")
        val aTex = GLES20.glGetAttribLocation(prog, "aTex")
        vertices.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(aPos)
        vertices.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun texParams(target: Int) {
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        fun compile(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) throw RuntimeException("shader: " + GLES20.glGetShaderInfoLog(shader))
            return shader
        }
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, compile(GLES20.GL_VERTEX_SHADER, vertexSrc))
        GLES20.glAttachShader(prog, compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc))
        GLES20.glLinkProgram(prog)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("link: " + GLES20.glGetProgramInfoLog(prog))
        return prog
    }

    fun release() {
        try { decoderSurface.release() } catch (_: Exception) {}
        try { surfaceTexture.release() } catch (_: Exception) {}
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
    }
}
