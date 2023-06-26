package com.limehug.speechlens
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Html
import android.text.method.ScrollingMovementMethod
import android.util.Log
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RelativeLayout
import androidx.camera.core.ExperimentalGetImage
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.ui.text.toUpperCase
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.translate.Translate
import com.google.cloud.translate.TranslateOptions
import com.google.cloud.translate.Translation
import com.google.mlkit.vision.common.InputImage
import com.limehug.speechlens.databinding.ActivityMainBinding
import java.io.File
import java.io.FileWriter
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.Locale


@ExperimentalGetImage class MainActivity : AppCompatActivity() {

    private var translateCount : Int = 0
    private var mediaRecorder: MediaRecorder? = null
    private var currentMode = "transcription"
    // Définissez un drapeau pour suivre si la reconnaissance audio est en cours
    private var isListening = false
    val MAX_LINES = 2

    private val recognizedWords = mutableListOf<String>()




    private lateinit var binding : ActivityMainBinding
    private var imageCapture:ImageCapture?=null
    private lateinit var textOverlayContainer: FrameLayout
    private lateinit var textView: TextView

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var speechRecognitionListener: RecognitionListener

    val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .build()
    val faceDetector = FaceDetection.getClient(options)

    private fun processImage(image: ImageProxy) {
        val mediaImage = image.image
        val rotation = image.imageInfo.rotationDegrees

        mediaImage?.let {
            val inputImage = InputImage.fromMediaImage(it, rotation)
            faceDetector.process(inputImage)
                .addOnSuccessListener { faces ->
                    // Traitez les visages détectés
                    processFaces(faces)
                }
                .addOnFailureListener { e ->
                    // Gérez les erreurs de détection de visage
                    Log.e(Constants.TAG, "Face detection failed: ${e.message}", e)
                }
                .addOnCompleteListener {
                    // Fermez l'image Proxy après avoir terminé le traitement
                    image.close()
                }
        } ?: image.close()
    }

    private fun processFaces(faces: List<Face>) {
        // Supprimer tous les TextView précédemment ajoutés
        textOverlayContainer.removeAllViews()

        for (face in faces) {
            val bounds = face.boundingBox // Récupérez les coordonnées du visage

            // Calculer les coordonnées du TextView en dessous du visage
            val textViewX = bounds.centerX().toFloat() - 50f
            val textViewY = bounds.bottom.toFloat() + 6*bounds.height().toFloat()

            if (textView.parent != null) {
                (textView.parent as ViewGroup).removeView(textView)
            }

            // Créer et personnaliser votre TextView
            textView.x = textViewX
            textView.y = textViewY
            textView.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            textView.maxWidth = 1000
            textView.movementMethod = ScrollingMovementMethod.getInstance()


            // Ajouter le TextView à votre conteneur textOverlayContainer
            textOverlayContainer.addView(textView)
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        val sharedPreferences = getSharedPreferences("Prefs", Context.MODE_PRIVATE)

        if(!sharedPreferences.contains("selectedLanguageCode")){
            save("selectedLanguageCode","en")
        }
        val selectedLanguageCode = get("selectedLanguageCode")
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        textOverlayContainer = findViewById(R.id.textOverlayContainer)
        textView = findViewById(R.id.subtitleTextView)


        mediaRecorder = null

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognitionListener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Toast.makeText(this@MainActivity, "Ready for speech", Toast.LENGTH_SHORT).show()
            }

            override fun onBeginningOfSpeech() {
                Toast.makeText(this@MainActivity, "Speech started", Toast.LENGTH_SHORT).show()
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}


            override fun onEndOfSpeech() {
                // Restart listening if isListening is true
                if (isListening) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        speechRecognizer.cancel()
                        startListening()
                    }, 100)
                }
            }



            override fun onError(error: Int) {
                Toast.makeText(this@MainActivity, "Speech recognition error", Toast.LENGTH_SHORT).show()
            }

            override fun onResults(results: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                matches?.firstOrNull()?.let { recognizedWord ->
                    Toast.makeText(this@MainActivity, "Mot Reconnu: $recognizedWord", Toast.LENGTH_SHORT).show()
                    addToPref("to translate", recognizedWord)
                    translateCount += 1
                    if (translateCount == 10) {
                        translate(selectedLanguageCode)
                        translateCount = 0
                        save("to translate", "")
                        if (currentMode == "translation"){
                            showText()
                        }
                    }

                    if (currentMode == "transcription" && !recognizedWords.contains(recognizedWord)) {
                        recognizedWords.add(recognizedWord)
                        runOnUiThread {
                            // Ajoute le mot uniquement s'il n'est pas déjà présent dans le texte
                            if (textView.text.isEmpty()) {
                                textView.append(recognizedWord)
                            } else {
                                textView.append(" $recognizedWord")
                            }

                            // Scroll to the last line
                            val layout = textView.layout
                            // Check if the number of lines exceeds the limit
                            if (textView.lineCount > MAX_LINES) {
                                val firstLineEnd = layout.getLineEnd(0)
                                textView.text = textView.text.subSequence(firstLineEnd + 1, textView.text.length)
                            }

                            addToPref("transcription", recognizedWord)
                        }
                    }
                }
            }



            override fun onEvent(eventType: Int, params: Bundle?) {}
        }



        val buttonRecord = findViewById<Button>(R.id.buttonRecord)
        buttonRecord.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                val permissions = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE)
                ActivityCompat.requestPermissions(this, permissions,0)
                Toast.makeText(this, "Recording could not start!", Toast.LENGTH_SHORT).show()

            } else {
                if (isListening) {
                    stopListening()
                    speechRecognizer.destroy()
                    buttonRecord.text = "Start"
                    isListening = false
                    textView.text = ""
                } else {
                    startListening()
                    buttonRecord.text = "Stop"
                    isListening = true
                    Toast.makeText(this@MainActivity, "En écoute", Toast.LENGTH_SHORT).show()
                }
            }
        }

        //Passer à la page des paramètres
        val settingsImage: ImageView = findViewById(R.id.settingsImage)
        settingsImage.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }


        val buttonMode = findViewById<Button>(R.id.buttonMode)
        buttonMode.setOnClickListener{
            if (currentMode == "transcription") {
                currentMode = "translation"
                Toast.makeText(this@MainActivity, "Mode translation", Toast.LENGTH_SHORT).show()
            } else if (currentMode == "translation") {
                currentMode = "transcription"
                Toast.makeText(this@MainActivity, "Mode transcription", Toast.LENGTH_SHORT).show()}
        }


        //CAMERA
        if(allPermissionsGranted()){
            startCamera()
        }else{
            ActivityCompat.requestPermissions(this,Constants.REQUIRED_PERMISSIONS,Constants.REQUEST_CODE_PERMISSIONS)
        }
    }


    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)



        speechRecognizer.setRecognitionListener(speechRecognitionListener)
        speechRecognizer.startListening(intent)
    }

    private fun stopListening() {
        // Stop listening for speech
        speechRecognizer.stopListening()
        speechRecognizer.cancel()
        speechRecognizer.destroy()
    }
    //CAMERA


    private fun startCamera(){
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also{mPreview ->
                mPreview.setSurfaceProvider(binding.cameraPreview.surfaceProvider)}

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(ContextCompat.getMainExecutor(this)) { image ->
                        // Traitez l'image
                        processImage(image)
                    }
                }

            imageCapture = ImageCapture.Builder().build()
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try{cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this,cameraSelector,preview,imageCapture, imageAnalyzer)

            }catch(e: Exception){
                Log.d(Constants.TAG,"startCamera fail:",e)
            }

        },ContextCompat.getMainExecutor(this))

        //startFaceDetection()
    }
    private fun allPermissionsGranted() =
        Constants.REQUIRED_PERMISSIONS.all{
            ContextCompat.checkSelfPermission(
                baseContext, it
            ) == PackageManager.PERMISSION_GRANTED
        }



    //Permet la traduction du fichier recording (via le bouton translate)
    private fun translate(selectedLanguageCode: String) {
        val recordingText = get("to translate")

        GlobalScope.launch(Dispatchers.IO) {
            try {
                // Initialize the translation API client
                val options = TranslateOptions.newBuilder()
                    .setCredentials(GoogleCredentials.fromStream(resources.openRawResource(R.raw.credentials)))
                    .build()
                val translate = options.service

                // Translate the recording text to English
                val translation: Translation = translate.translate(recordingText, Translate.TranslateOption.targetLanguage(selectedLanguageCode))

                // Get the translated text
                val translatedText: String = Html.fromHtml(translation.translatedText, Html.FROM_HTML_MODE_LEGACY).toString()


                // Write the translated text to the translation file
                save("translated", translatedText)

                launch(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Audio translation completed!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    //Permet d'afficher sur l'app le contenu d'un fichier texte (via le bouton Show)
    private fun showText() {

        val textToShow = get("translate")
        textView.setBackgroundColor(Color.BLACK)
        textView.text = textToShow
        save("translate", "")

    }


    private fun save(location: String,value: String) {
        val sharedPreferences = getSharedPreferences("Prefs", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.putString(location, value)
        editor.apply()
        Log.i("languages","$value saved in $location")
    }

    private fun addToPref(location: String,value: String) {
        val sharedPreferences = getSharedPreferences("Prefs", Context.MODE_PRIVATE)
        var string = get(location)
        string += " $value"
        val editor = sharedPreferences.edit()
        editor.putString(location, string)
        editor.apply()
        Log.i("languages","$value saved in $location")
    }

    private fun get(location: String): String {
        return getSharedPreferences("Prefs", Context.MODE_PRIVATE).getString(location, null).toString()
    }

}