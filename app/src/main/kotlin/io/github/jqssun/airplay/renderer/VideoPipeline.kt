package io.github.jqssun.airplay.renderer

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

// decodes into an app-owned SurfaceTexture that outlives display surface
// gl thread blits it to any surface attached, so fullscreen toggles re-point display without restarting codec
class VideoPipeline {

    private val lock = Object()
    private var thread: Thread? = null
    @Volatile private var running = false

    private var egl: EglCore? = null
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var winW = 0
    private var winH = 0

    private var oesTex = 0
    private var program = 0
    private var aPos = 0
    private var aTex = 0
    private var uTexMatrix = 0
    private val texMatrix = FloatArray(16)
    private var hasFrame = false

    private var surfaceTexture: SurfaceTexture? = null
    @Volatile var inputSurface: Surface? = null; private set
    val isRunning: Boolean get() = running

    @Volatile private var frameAvailable = false
    private var pendingDisplay: Surface? = null
    private var displayDirty = false
    private var sizeDirty = false
    @Volatile private var videoW = 0
    @Volatile private var videoH = 0

    fun start() = synchronized(lock) {
        if (running) return@synchronized
        check(thread?.isAlive != true) { "Video pipeline is still releasing" }
        running = true
        hasFrame = false
        frameAvailable = false
        displayDirty = true
        thread = Thread({ _loop() }, "VideoPipeline").also { it.start() }
        val deadline = System.nanoTime() + 3_000_000_000L
        while (inputSurface == null && running) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) {
                running = false
                lock.notifyAll()
                error("Video pipeline initialization timed out")
            }
            lock.wait((remaining / 1_000_000L).coerceAtLeast(1L))
        }
        check(inputSurface != null && running) { "Video pipeline initialization failed" }
    }

    fun setDisplaySurface(surface: Surface?) = synchronized(lock) {
        pendingDisplay = surface
        displayDirty = true
        lock.notifyAll()
    }

    fun setVideoSize(w: Int, h: Int) = synchronized(lock) {
        videoW = w
        videoH = h
        sizeDirty = true
        lock.notifyAll()
    }

    fun release() {
        val worker = synchronized(lock) {
            running = false
            lock.notifyAll()
            thread
        }
        // A broken EGL driver must not indefinitely block the service's main thread.
        worker?.join(2_000)
        if (worker?.isAlive == true) Log.w(TAG, "GL teardown still pending")
    }

    private fun _loop() {
        try {
            egl = EglCore()
            _initGl()
            Matrix.setIdentityM(texMatrix, 0)
            synchronized(lock) {
                if (!running) return
                inputSurface = Surface(surfaceTexture)
                lock.notifyAll()
            }
            while (true) {
                var newDisplay: Surface? = null
                var displayChanged = false
                var doFrame = false
                val resize: Boolean
                synchronized(lock) {
                    while (running && !frameAvailable && !displayDirty && !sizeDirty) lock.wait()
                    if (running && displayDirty) {
                        newDisplay = pendingDisplay
                        displayChanged = true
                        displayDirty = false
                    }
                    if (running && frameAvailable) {
                        frameAvailable = false
                        doFrame = true
                    }
                    resize = sizeDirty
                    sizeDirty = false
                }
                if (!running) break
                if (resize && videoW > 0 && videoH > 0) surfaceTexture?.setDefaultBufferSize(videoW, videoH)
                if (displayChanged) _bindDisplay(newDisplay)
                if (doFrame) _consumeAndDraw()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Video pipeline failed", e)
        } finally {
            _releaseGl()
            synchronized(lock) {
                running = false
                thread = null
                lock.notifyAll()
            }
        }
    }

    private fun _bindDisplay(surface: Surface?) {
        val egl = egl ?: return
        if (window != EGL14.EGL_NO_SURFACE) {
            egl.makeCurrent()
            egl.destroySurface(window)
            window = EGL14.EGL_NO_SURFACE
        }
        if (surface == null || !surface.isValid) return
        window = egl.windowSurface(surface)
        if (window == EGL14.EGL_NO_SURFACE) {
            Log.w(TAG, "eglCreateWindowSurface failed: ${EGL14.eglGetError()}")
            return
        }
        if (!egl.makeCurrent(window)) {
            egl.destroySurface(window)
            window = EGL14.EGL_NO_SURFACE
            egl.makeCurrent()
            return
        }
        winW = egl.query(window, EGL14.EGL_WIDTH)
        winH = egl.query(window, EGL14.EGL_HEIGHT)
        // an idle source sends no new frames, so repaint last one or new surface stays black
        if (hasFrame) _render()
    }

    private fun _consumeAndDraw() {
        val st = surfaceTexture ?: return
        val egl = egl ?: return
        // Continue consuming on the pbuffer while the Activity surface is absent.
        // Also refresh its transform there: the phone can rotate while hidden.
        val canDraw = window != EGL14.EGL_NO_SURFACE && egl.makeCurrent(window)
        if (!canDraw) check(egl.makeCurrent()) { "Cannot activate video GL context" }
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)
        hasFrame = true
        if (canDraw) _render()
    }

    private fun _render() {
        GLES20.glViewport(0, 0, winW, winH)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, POS)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, TEX)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
        val core = egl ?: return
        if (!core.swap(window)) {
            core.makeCurrent()
            core.destroySurface(window)
            window = EGL14.EGL_NO_SURFACE
        }
    }

    private fun _initGl() {
        program = _buildProgram()
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        oesTex = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        surfaceTexture = SurfaceTexture(oesTex).also {
            if (videoW > 0 && videoH > 0) it.setDefaultBufferSize(videoW, videoH)
            it.setOnFrameAvailableListener {
                synchronized(lock) { frameAvailable = true; lock.notifyAll() }
            }
        }
    }

    private fun _buildProgram(): Int {
        val vs = _shader(GLES20.GL_VERTEX_SHADER, VERT)
        val fs = _shader(GLES20.GL_FRAGMENT_SHADER, FRAG)
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vs)
            GLES20.glAttachShader(it, fs)
            GLES20.glLinkProgram(it)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, linked, 0)
            if (linked[0] == 0) {
                val message = GLES20.glGetProgramInfoLog(it)
                GLES20.glDeleteProgram(it)
                error("Video shader link failed: $message")
            }
        }
    }

    private fun _shader(type: Int, src: String): Int {
        return GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src)
            GLES20.glCompileShader(it)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val message = GLES20.glGetShaderInfoLog(it)
                GLES20.glDeleteShader(it)
                error("Video shader compile failed: $message")
            }
        }
    }

    private fun _releaseGl() {
        runCatching { surfaceTexture?.setOnFrameAvailableListener(null) }
        runCatching { surfaceTexture?.release() }
        surfaceTexture = null
        runCatching { inputSurface?.release() }
        inputSurface = null
        egl?.let {
            runCatching { if (window != EGL14.EGL_NO_SURFACE) it.destroySurface(window) }
            runCatching { it.close() }
        }
        egl = null
        window = EGL14.EGL_NO_SURFACE
        hasFrame = false
    }

    companion object {
        private const val TAG = "VideoPipeline"

        private val POS = _fb(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        private val TEX = _fb(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

        private fun _fb(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply { put(a); position(0) }

        private const val VERT =
            "attribute vec2 aPos;\n" +
            "attribute vec2 aTex;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "varying vec2 vTex;\n" +
            "void main() {\n" +
            "  gl_Position = vec4(aPos, 0.0, 1.0);\n" +
            "  vTex = (uTexMatrix * vec4(aTex, 0.0, 1.0)).xy;\n" +
            "}\n"

        private const val FRAG =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTex;\n" +
            "uniform samplerExternalOES sTex;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(sTex, vTex);\n" +
            "}\n"
    }
}
