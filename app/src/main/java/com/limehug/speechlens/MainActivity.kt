package com.limehug.speechlens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Html
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


@ExperimentalGetImage class MainActivity : AppCompatActivity() {

    private var output: String? = null
    private var mediaRecorder: MediaRecorder? = null
    private var currentMode = "transcription"


    //camera
    private lateinit var binding : ActivityMainBinding
    private var imageCapture:ImageCapture?=null
    private lateinit var textOverlayContainer: FrameLayout
    private lateinit var textView: TextView


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
            val textViewX = bounds.centerX().toFloat() + bounds.width().toFloat()
            val textViewY = bounds.bottom.toFloat() + 6*bounds.height().toFloat()


            if (textView.parent != null) {
                (textView.parent as ViewGroup).removeView(textView)
            }

            // Créer et personnaliser votre TextView
            textView.x = textViewX
            textView.y = textViewY
            textView.setTextColor(Color.WHITE)
            textView.setBackgroundColor(Color.BLACK)
            textView.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

            // Ajouter le TextView à votre conteneur textOverlayContainer
            textOverlayContainer.addView(textView)
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        textOverlayContainer = findViewById(R.id.textOverlayContainer)
        textView = findViewById(R.id.subtitleTextView)


        mediaRecorder = null
        output = getExternalFilesDir(null)?.absolutePath + "/recording.mp3"
        println(output)

        val buttonRecord = findViewById<Button>(R.id.buttonRecord)
        buttonRecord.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                val permissions = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE)
                ActivityCompat.requestPermissions(this, permissions,0)
                Toast.makeText(this, "Recording could not start!", Toast.LENGTH_SHORT).show()

            } else {
                recognizeAudio()
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
            } else if (currentMode == "translation") { currentMode = "transcription"}
            showText()
        }


        //CAMERA
        if(allPermissionsGranted()){
            startCamera()
        }else{
            ActivityCompat.requestPermissions(this,Constants.REQUIRED_PERMISSIONS,Constants.REQUEST_CODE_PERMISSIONS)
        }
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
    }
    fun onRequestPermissionResult(
    requestCode:Int,
    permissions:Array<out String>, grantResults: IntArray) {
        if(requestCode == Constants.REQUEST_CODE_PERMISSIONS){
            if (allPermissionsGranted()){
                startCamera()
            }else{
                Toast.makeText(this,"Permission not granted by the user.",Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
    private fun allPermissionsGranted() =
        Constants.REQUIRED_PERMISSIONS.all{
            ContextCompat.checkSelfPermission(
                baseContext, it
            ) == PackageManager.PERMISSION_GRANTED
        }


    //val settingsImage = findViewById<ImageView>(R.id.settingsImage)


    //Permet la traduction du fichier recording (via le bouton translate)
    private fun translateAudio() {
        val recordingFile = File(getExternalFilesDir(null), "recording.txt")
        val translationFile = File(getExternalFilesDir(null), "translation.txt")

        val recordingText = recordingFile.readText()

        GlobalScope.launch(Dispatchers.IO) {
            try {
                // Initialize the translation API client
                val options = TranslateOptions.newBuilder()
                    .setCredentials(GoogleCredentials.fromStream(resources.openRawResource(R.raw.credentials)))
                    .build()
                val translate = options.service

                // Translate the recording text to English
                val translation: Translation = translate.translate(recordingText, Translate.TranslateOption.targetLanguage("en"))

                // Get the translated text
                val translatedText: String = Html.fromHtml(translation.translatedText, Html.FROM_HTML_MODE_LEGACY).toString()


                // Write the translated text to the translation file
                translationFile.writeText(translatedText)

                launch(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Audio translation completed!", Toast.LENGTH_SHORT).show()
                    showText()
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    //Permet d'afficher sur l'app le contenu d'un fichier texte (via le bouton Show)
    private fun showText() {
        val filePath = getExternalFilesDir(null)?.absolutePath + "/recording.txt"
        val filePath2 = getExternalFilesDir(null)?.absolutePath + "/translation.txt"
        var textToShow = ""

        try {
            if (currentMode == "transcription") {
                val file = File(filePath)
                if (file.exists()) {
                    val text = file.readText()
                    textToShow = text
                } else {
                    textToShow = "File not found"
                }
            } else if (currentMode == "translation") {
                val file2 = File(filePath2)
                if (file2.exists()) {
                    val text = file2.readText()
                    textToShow = text
                } else {
                    textToShow = "File not found"
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
            textToShow = "Error reading file"
        }

        textView.text = textToShow
    }


    //Gère la reconnaissance vocale (du bouton Record)
    private fun recognizeAudio() {
        val outputFile = getExternalFilesDir(null)?.absolutePath + "/recording.txt"

        val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val speechRecognitionListener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Toast.makeText(this@MainActivity, "Ready for speech", Toast.LENGTH_SHORT).show()
            }

            override fun onBeginningOfSpeech() {
                Toast.makeText(this@MainActivity, "Speech started", Toast.LENGTH_SHORT).show()
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Toast.makeText(this@MainActivity, "Speech ended", Toast.LENGTH_SHORT).show()
            }

            override fun onError(error: Int) {
                Toast.makeText(this@MainActivity, "Speech recognition error", Toast.LENGTH_SHORT).show()
            }

            override fun onResults(results: Bundle?) {
                val recognizedTexts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!recognizedTexts.isNullOrEmpty()) {
                    val recognizedText = recognizedTexts[0]
                    writeTextToFile(outputFile, recognizedText)
                    Toast.makeText(this@MainActivity, "Speech recognized and saved to file", Toast.LENGTH_SHORT).show()
                    translateAudio()
                    showText()
                } else {
                    Toast.makeText(this@MainActivity, "No speech recognized", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

        try {
            speechRecognizer.setRecognitionListener(speechRecognitionListener)
            speechRecognizer.startListening(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Speech recognition not available", Toast.LENGTH_SHORT).show()
        }
    }

    //Rédige un fichier texte dans les fichiers de l'app
    private fun writeTextToFile(filePath: String, text: String) {
        try {
            val file = File(filePath)
            file.createNewFile()
            val writer = FileWriter(file)
            writer.append(text)
            writer.flush()
            writer.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

}