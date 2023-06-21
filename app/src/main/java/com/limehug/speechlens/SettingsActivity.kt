package com.limehug.speechlens

import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Spinner
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.translate.Language
import com.google.cloud.translate.Translate
import com.google.cloud.translate.TranslateOptions
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@Suppress("NAME_SHADOWING")
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)


        //Passer à la page principale
        val settingsImage: ImageView = findViewById(R.id.settingsImage)
        settingsImage.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
        }

        runBlocking {
            launch(Dispatchers.IO) {
                try {
                    getLanguagesList()

                } catch (e: Exception) {
                    Log.i("languages","getLanguagesList failed")
                }
            }
        }
    }
    private fun getLanguagesList() {
        // Récupérer le code de langue sélectionné par défaut depuis les préférences
        val selectedLanguageCode = get("selectedLanguageCode")

        // Charger la liste des langues à partir du fichier JSON dans les préférences
        val jsonLanguages = get("languages")
        val type = object : TypeToken<List<Language>>() {}.type
        val languages = Gson().fromJson<List<Language>>(jsonLanguages, type)

        // Convertir la liste des langues en une liste de noms de langue
        val languageNames = languages.map { language -> language.name }

        // Obtenir une référence au Spinner depuis la mise en page XML
        val languageSpinner = findViewById<Spinner>(R.id.languageSpinner)

        // Créer un adaptateur pour le Spinner
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languageNames)

        // Spécifier le style de la liste déroulante
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        // Appliquer l'adaptateur au Spinner
        languageSpinner.adapter = adapter

        // Trouver l'index de l'option correspondant au code de langue sélectionné
        val selectedLanguageIndex = languages.indexOfFirst { it.code == selectedLanguageCode }
        save("selectedLanguagueIndex",selectedLanguageIndex.toString())

        // Mettre à jour l'index de sélection du Spinner
        if (selectedLanguageIndex != -1) {
            languageSpinner.setSelection(selectedLanguageIndex)
        }

        // Écouteur d'événements pour le Spinner
        languageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedLanguage = languages[position]
                val selectedLanguageCode = selectedLanguage.code
                save("selectedLanguageCode", selectedLanguageCode)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Rien à faire ici
            }
        }
    }


    private fun save(location: String,value: String) {
        val sharedPreferences = getSharedPreferences("Prefs", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.putString(location, value)
        editor.apply()
        Log.i("languages","$value saved in $location")
    }

    private fun get(location: String): String {
        return getSharedPreferences("Prefs", Context.MODE_PRIVATE).getString(location, null).toString()
    }
}