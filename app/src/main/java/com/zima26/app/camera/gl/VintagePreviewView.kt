package com.zima26.app.camera.gl

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

/**
 * Vista previa de la cámara con el filtro antiguo aplicado en tiempo real mediante un
 * shader de OpenGL ES 2.0. Funciona en todas las versiones soportadas (Android 8+).
 */
@SuppressLint("ViewConstructor")
class VintagePreviewView(context: Context) : GLSurfaceView(context) {

    val renderer = VintagePreviewRenderer(this)

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        // Se redibuja cada vez que llega un fotograma de la cámara.
        renderMode = RENDERMODE_WHEN_DIRTY
    }
}

/**
 * Renderizador: recibe los fotogramas de CameraX en una textura externa (OES) y los
 * dibuja con el shader "simplificado": blanco y negro ortocromático, curva de
 * contraste, sepia, viñeta y grano animado.
 *
 * Implementa [Preview.SurfaceProvider] para entregarle a CameraX la superficie.
 */
class VintagePreviewRenderer(private val view: GLSurfaceView) : GLSurfaceView.Renderer, Preview.SurfaceProvider {

    /** Estilo de época (ordinal de PhotoTone): 0 sepia, 1 ByN, 2 daguerrotipo, 3 cianotipia,
     *  4 Polaroid, 5 Kodachrome, 6 postal coloreada. */
    @Volatile
    var style: Int = 0

    /** Se llama (en el hilo principal) si se perdió el contexto GL y hay que pedir otra superficie. */
    @Volatile
    var onSurfaceLost: (() -> Unit)? = null

    /** Textura + SurfaceTexture asociadas a una petición de CameraX. */
    private class Stream(val texId: Int, val surfaceTexture: SurfaceTexture, val width: Int, val height: Int)

    private val mainExecutor = ContextCompat.getMainExecutor(view.context)
    private val lock = Any()
    private var pending: SurfaceRequest? = null

    // Solo se accede desde el hilo GL
    private var current: Stream? = null
    private var glReady = false
    private var program = 0
    private var viewWidth = 1
    private var viewHeight = 1
    private var frame = 0
    private val stMatrix = FloatArray(16)
    private val extraMatrix = FloatArray(16)

    // Información de transformación que envía CameraX (hilo principal -> hilo GL)
    @Volatile private var rotationDegrees = 0
    @Volatile private var mirroring = false
    @Volatile private var hasCameraTransform = true

    private val quad: FloatBuffer = floatBuffer(
        // x, y, s, t
        -1f, -1f, 0f, 0f,
        1f, -1f, 1f, 0f,
        -1f, 1f, 0f, 1f,
        1f, 1f, 1f, 1f
    )

    // ------------------------------------------------------------ CameraX

    override fun onSurfaceRequested(request: SurfaceRequest) {
        synchronized(lock) {
            pending?.willNotProvideSurface()
            pending = request
        }
        view.queueEvent { servePending() }
    }

    /** Crea una textura nueva para la petición pendiente y se la entrega a CameraX. */
    private fun servePending() {
        if (!glReady) return
        val request = synchronized(lock) { pending.also { pending = null } } ?: return

        val texId = createOesTexture()
        val st = SurfaceTexture(texId)
        val res = request.resolution
        st.setDefaultBufferSize(res.width, res.height)
        st.setOnFrameAvailableListener { view.requestRender() }
        val surface = Surface(st)
        val stream = Stream(texId, st, res.width, res.height)
        current = stream

        request.setTransformationInfoListener(mainExecutor) { info ->
            rotationDegrees = info.rotationDegrees
            mirroring = info.isMirroring
            hasCameraTransform = info.hasCameraTransform()
        }
        request.provideSurface(surface, mainExecutor) {
            // CameraX ya no usa esta superficie: liberar todo en el hilo GL.
            surface.release()
            view.queueEvent {
                if (current === stream) current = null
                st.release()
                GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
            }
        }
    }

    // ------------------------------------------------------------ GLSurfaceView.Renderer

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Si ya había un flujo, el contexto GL se ha perdido: sus texturas no sirven.
        val lost = current != null
        current = null
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        glReady = true
        servePending()
        if (lost) mainExecutor.execute { onSurfaceLost?.invoke() }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClearColor(0.07f, 0.065f, 0.06f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val stream = current ?: return

        try {
            stream.surfaceTexture.updateTexImage()
        } catch (e: RuntimeException) {
            Log.w(TAG, "updateTexImage falló", e)
            return
        }
        stream.surfaceTexture.getTransformMatrix(stMatrix)

        // Matriz adicional solo si la superficie NO trae la rotación de la cámara.
        Matrix.setIdentityM(extraMatrix, 0)
        val swapped: Boolean
        if (hasCameraTransform) {
            // La matriz de SurfaceTexture ya gira según el sensor (y espeja la frontal).
            swapped = abs(stMatrix[0]) < abs(stMatrix[1])
        } else {
            Matrix.translateM(extraMatrix, 0, 0.5f, 0.5f, 0f)
            Matrix.rotateM(extraMatrix, 0, rotationDegrees.toFloat(), 0f, 0f, 1f)
            if (mirroring) Matrix.scaleM(extraMatrix, 0, -1f, 1f, 1f)
            Matrix.translateM(extraMatrix, 0, -0.5f, -0.5f, 0f)
            swapped = rotationDegrees % 180 != 0
        }

        // Encajar la imagen completa (sin recortar) en la vista.
        val contentAspect = if (swapped) stream.height.toFloat() / stream.width else stream.width.toFloat() / stream.height
        val viewAspect = viewWidth.toFloat() / viewHeight
        val scaleX: Float
        val scaleY: Float
        if (contentAspect > viewAspect) {
            scaleX = 1f; scaleY = viewAspect / contentAspect
        } else {
            scaleX = contentAspect / viewAspect; scaleY = 1f
        }

        GLES20.glUseProgram(program)
        val aPos = GLES20.glGetAttribLocation(program, "aPosition")
        val aTex = GLES20.glGetAttribLocation(program, "aTexCoord")
        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTex)

        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uSTMatrix"), 1, false, stMatrix, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uExtra"), 1, false, extraMatrix, 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uScale"), scaleX, scaleY)
        frame = (frame + 1) % 997
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uTime"), frame / 997f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uStyle"), style.toFloat())
        // El grano mide ~1.5 px de pantalla
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(program, "uGrainRes"),
            viewWidth * scaleX / 1.5f, viewHeight * scaleY / 1.5f
        )

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, stream.texId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
    }

    // ------------------------------------------------------------ utilidades GL

    private fun createOesTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "Error de shader: " + GLES20.glGetShaderInfoLog(shader) }
            return shader
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "Error al enlazar: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    private fun floatBuffer(vararg values: Float): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(values); position(0)
        }

    companion object {
        private const val TAG = "VintagePreview"

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uSTMatrix;
            uniform mat4 uExtra;
            uniform vec2 uScale;
            varying vec2 vTex;
            varying vec2 vPos;
            void main() {
                gl_Position = vec4(aPosition.xy * uScale, 0.0, 1.0);
                vTex = (uSTMatrix * uExtra * vec4(aTexCoord, 0.0, 1.0)).xy;
                vPos = aTexCoord;
            }
        """

        /**
         * Versión simplificada del filtro completo:
         * gris ortocromático -> curva (negros lavados, blancos apagados) -> grano animado
         * más fuerte en sombras -> viñeta elíptica -> virado según el estilo.
         */
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform samplerExternalOES uTex;
            uniform float uTime;
            uniform float uStyle;
            uniform vec2 uGrainRes;
            varying vec2 vTex;
            varying vec2 vPos;

            float hash(vec2 p) {
                vec3 p3 = fract(vec3(p.xyx) * 0.1031);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z);
            }

            void main() {
                vec3 c = texture2D(uTex, vTex).rgb;
                bool colorStyle = uStyle > 3.5;
                // Película ortocromática (el rojo sale oscuro); los estilos en color usan luma estándar
                float g = colorStyle ? dot(c, vec3(0.299, 0.587, 0.114)) : dot(c, vec3(0.25, 0.45, 0.30));
                float y0 = dot(c, vec3(0.299, 0.587, 0.114));
                // Curva de contraste
                g = clamp((g - 0.06) / 0.86, 0.0, 1.0);
                g = 0.6 * g * g * (3.0 - 2.0 * g) + 0.4 * g;
                g = 0.07 + g * 0.83;
                // Grano animado: suma de dos valores aleatorios ~ gaussiano
                vec2 cell = floor(vPos * uGrainRes);
                vec2 seed = cell + vec2(uTime * 517.0, uTime * 293.0);
                float n = (hash(seed) + hash(seed + 71.7)) - 1.0;
                g += n * 0.09 * (0.6 + 0.4 * (1.0 - g));
                // Viñeta fuerte
                vec2 d = vPos * 2.0 - 1.0;
                g *= clamp(1.0 - 0.55 * pow(length(d), 2.2), 0.2, 1.0);
                vec3 col;
                if (uStyle < 0.5) {          // sepia
                    col = vec3(g + 0.04, g * 0.93 + 0.03, g * 0.80 + 0.02) * vec3(1.05, 0.95, 0.82);
                } else if (uStyle < 1.5) {   // blanco y negro
                    col = vec3(g);
                } else if (uStyle < 2.5) {   // daguerrotipo
                    col = vec3(0.03 + 0.88 * g, 0.04 + 0.91 * g, 0.07 + 0.95 * g);
                } else if (uStyle < 3.5) {   // cianotipia
                    col = mix(vec3(0.04, 0.13, 0.30), vec3(0.86, 0.93, 0.96), pow(g, 0.9));
                } else {
                    // Estilos en color: luminancia procesada + color original con su saturación y tinte
                    vec3 chroma = c - vec3(y0);
                    float sat;
                    vec3 tint;
                    if (uStyle < 4.5) {        // Polaroid
                        g = 0.10 + 0.80 * g; sat = 0.75; tint = vec3(0.02, 0.02, -0.05);
                    } else if (uStyle < 5.5) { // Kodachrome
                        g = 0.7 * g * g * (3.0 - 2.0 * g) + 0.3 * g; sat = 1.35; tint = vec3(0.03, 0.0, -0.03);
                    } else {                   // postal coloreada
                        sat = 0.45; tint = vec3(0.04, 0.01, -0.04);
                    }
                    col = vec3(g) + chroma * sat + tint;
                }
                gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
            }
        """
    }
}
