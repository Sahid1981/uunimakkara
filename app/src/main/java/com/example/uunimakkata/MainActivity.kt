package com.example.uunimakkata
import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.Calendar
import java.util.Locale

data class SausageResult(
    val restaurant: String,
    val link: String,
    val additionalInfo: String, 
    val distanceInKm: Double? = null
)

class MainActivity : AppCompatActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var currentLocation: Location? = null
    private var currentCity: String = "jyväskylä" // Default city if location fails
    private lateinit var tvLocation: TextView
    private lateinit var webView: WebView

    private val searchWords = listOf("uunimakkara", "uunimakkaraa", "uunimakkarat", "uunilenkki", "uunilenkkiä")
    private val weekDays = listOf("Ma ", "Ti ", "Ke ", "To ", "Pe ", "La ", "Su ")
    
    // Search radius constant
    private val maxSearchRadiusKm = 30

    // Week search related variables
    private val weekFindings = mutableListOf<SausageResult>()
    private var currentDayIndex = 0
    // Number to differentiate days in the URL hash
    private val daysToSearch = listOf("5", "6", "7", "8", "9") // Pe, La, Su, Ma, Ti (hash values from the HTML)

    @SuppressLint("SetTextI18n")
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            ) {
                updateLocation()
            } else {
                tvLocation.text = "Sijaintilupa evätty. Käytetään oletuksena " + currentCity
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvLocation = findViewById(R.id.tvSijainti)
        webView = findViewById(R.id.hiddenWebView)
        
        // Configure WebView
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.addJavascriptInterface(WebAppInterface(), "Android")

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        checkPermissionsAndUpdateLocation()

        val btnToday = findViewById<Button>(R.id.btnTanaan)
        val btnWeek = findViewById<Button>(R.id.btnViikko)

        btnToday.setOnClickListener {
            startSearch(onlyToday = true)
        }

        btnWeek.setOnClickListener {
            startSearch(onlyToday = false)
        }
    }

    // JavaScript interface for capturing HTML
    inner class WebAppInterface {
        @JavascriptInterface
        fun processHTML(html: String) {
            // HTML received from WebView, continue processing
            processReceivedHTML(html)
        }
    }
    
    private var searchOnlyToday = false
    private var loadingDialog: AlertDialog? = null

    private fun startSearch(onlyToday: Boolean) {
        searchOnlyToday = onlyToday
        
        loadingDialog = AlertDialog.Builder(this)
            .setTitle("Uunimakkara Tutka")
            .setMessage("Ladataan sivua ja painellaan nappeja (tämä kestää hetken)...")
            .setCancelable(false)
            .create()
        loadingDialog?.show()

        var searchCity = currentCity.lowercase().replace("ä", "a").replace("ö", "o").replace("å", "a")
        val url = "https://www.lounaat.info/" + searchCity
        
        // Load page in WebView
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // When page is loaded, start automation
                automaticActions()
            }
        }
        webView.loadUrl(url)
    }
    
    private fun automaticActions() {
        CoroutineScope(Dispatchers.Main).launch {
            if (searchOnlyToday) {
                // Today view: just load current day
                loadAndProcessCurrentPage()
            } else {
                // Week view: iterate through all days
                weekFindings.clear()
                currentDayIndex = 0
                loadNextDay()
            }
        }
    }
    
    private suspend fun scrollAndClickShowMore() {
        delay(1000)
        webView.evaluateJavascript("window.scrollTo(0, document.body.scrollHeight);", null)
        
        repeat(2) {
            delay(1500)
            val clickJs = """
                var btn = document.querySelector('.button.showmore');
                if (btn) {
                    btn.click();
                }
            """.trimIndent()
            webView.evaluateJavascript(clickJs, null)
            
            delay(2500)
            webView.evaluateJavascript("window.scrollTo(0, document.body.scrollHeight);", null)
        }
        
        delay(1000)
    }
    
    private suspend fun loadAndProcessCurrentPage() {
        scrollAndClickShowMore()
        
        // Capture HTML
        webView.evaluateJavascript(
            "window.Android.processHTML('<html>'+document.getElementsByTagName('html')[0].innerHTML+'</html>');",
            null
        )
    }
    
    private fun loadNextDay() {
        if (currentDayIndex >= daysToSearch.size) {
            // All days processed, show results
            CoroutineScope(Dispatchers.Main).launch {
                loadingDialog?.dismiss()
                val sorted = weekFindings.sortedBy { it.distanceInKm ?: Double.MAX_VALUE }
                showResults(sorted, false)
            }
            return
        }
        
        CoroutineScope(Dispatchers.Main).launch {
            val dayHash = daysToSearch[currentDayIndex]
            
            // Click the day filter link
            delay(1000)
            val clickDayJs = """
                var links = document.querySelectorAll('.dayview-filter a');
                for (var i = 0; i < links.length; i++) {
                    if (links[i].href.includes('#$dayHash')) {
                        links[i].click();
                        break;
                    }
                }
            """.trimIndent()
            webView.evaluateJavascript(clickDayJs, null)
            
            // Wait for page to update
            delay(2000)
            
            // Scroll and click "show more"
            scrollAndClickShowMore()
            
            // Capture HTML for this day
            webView.evaluateJavascript(
                "window.Android.processHTML('<html>'+document.getElementsByTagName('html')[0].innerHTML+'</html>');",
                null
            )
        }
    }

    private fun processReceivedHTML(html: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val doc = Jsoup.parse(html)
            if (searchOnlyToday) {
                // For today view, analyze and show results immediately
                analyzeDocument(doc, true)
            } else {
                // For week view, collect results and continue to next day
                val dayResults = analyzeDayDocument(doc)
                weekFindings.addAll(dayResults)
                currentDayIndex++
                withContext(Dispatchers.Main) {
                    loadNextDay()
                }
            }
        }
    }

    private fun checkPermissionsAndUpdateLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            updateLocation()
        } else {
            requestPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    @SuppressLint("MissingPermission", "SetTextI18n")
    private fun updateLocation() {
        tvLocation.text = "Paikannetaan..." // This is shown immediately

        // Set timeout if location takes too long
        val handler = Handler(Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            if (tvLocation.text == "Paikannetaan...") {
                tvLocation.text = "Paikannus aikakatkaistiin. Tarkista GPS.\n Käytetään " + currentCity
            }
        }
        handler.postDelayed(timeoutRunnable, 15000) 

        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                if (location != null) {
                    handler.removeCallbacks(timeoutRunnable)
                    handleLocation(location)
                } else {
                    // If no last location, request new one
                    val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000).build()
                    fusedLocationClient.requestLocationUpdates(locationRequest, object : LocationCallback() {
                        override fun onLocationResult(locationResult: LocationResult) {
                            handler.removeCallbacks(timeoutRunnable)
                            val loc = locationResult.lastLocation
                            if (loc != null) {
                                handleLocation(loc)
                            } else {
                                tvLocation.text = "Sijaintia ei saatu."
                            }
                            fusedLocationClient.removeLocationUpdates(this)
                        }
                    }, Looper.getMainLooper())
                }
            }.addOnFailureListener { e ->
                handler.removeCallbacks(timeoutRunnable)
                tvLocation.text = "Sijaintivirhe: ${e.message}"
            }
        } catch (e: Exception) {
            tvLocation.text = "Virhe käynnistettäessä: ${e.message}"
        }
    }

    @SuppressLint("SetTextI18n")
    private fun handleLocation(location: Location) {
        currentLocation = location
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val geocoder = Geocoder(this@MainActivity, Locale("fi", "FI"))
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                if (!addresses.isNullOrEmpty()) {
                    val city = addresses[0].locality
                    if (!city.isNullOrEmpty()) {
                        currentCity = city
                        withContext(Dispatchers.Main) {
                            tvLocation.text = "Sijainti: $city (Haku säteellä ${maxSearchRadiusKm}km)"
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private suspend fun analyzeDocument(doc: Document, onlyToday: Boolean) {
        val findings = analyzeDayDocument(doc, onlyToday)
        val sorted = findings.sortedBy { it.distanceInKm ?: Double.MAX_VALUE }

        withContext(Dispatchers.Main) {
            loadingDialog?.dismiss()
            showResults(sorted, onlyToday)
        }
    }
    
    private suspend fun analyzeDayDocument(doc: Document, checkToday: Boolean = false): List<SausageResult> {
        val findings = mutableListOf<SausageResult>()
        val geocoder = Geocoder(this@MainActivity, Locale("fi", "FI")) 

        try {
            val pageDay = getPageDay(doc)
            
            val items = doc.select("div.menu.item") 
            val candidates = if (items.isEmpty()) doc.select("div.item") else items
            
            val todayName = getTodayName() 

            for (item in candidates) {
                val itemText = item.text().lowercase()
                
                if (!containsSearchWord(itemText)) continue

                val restaurantElement = item.select("h3 a")
                if (restaurantElement.isEmpty()) continue
                
                val restaurantName = restaurantElement.text()
                val restaurantLink = restaurantElement.attr("abs:href")

                var distance: Double? = null
                var skipRestaurant = false
                
                if (currentLocation != null) {
                    var address = item.select(".item-address").text()
                    if (address.isEmpty()) address = item.select("address").text()
                    val searchAddress = if (address.isNotEmpty()) "$address, $currentCity" else "$restaurantName, $currentCity"
                    
                    try {
                         @Suppress("DEPRECATION")
                         val addresses = geocoder.getFromLocationName(searchAddress, 1)
                         if (!addresses.isNullOrEmpty()) {
                             val target = Location("Target")
                             target.latitude = addresses[0].latitude
                             target.longitude = addresses[0].longitude
                             distance = currentLocation!!.distanceTo(target) / 1000.0
                             
                             if (distance > maxSearchRadiusKm) {
                                 skipRestaurant = true
                             }
                         }
                    } catch (e: Exception) { }
                }

                if (skipRestaurant) continue

                var additionalInfo = ""
                
                if (checkToday) {
                    if (isSausageToday(itemText, todayName, pageDay)) {
                        additionalInfo = "Mahdollisesti tänään"
                        findings.add(SausageResult(restaurantName, restaurantLink, additionalInfo, distance))
                    }
                } else {
                    // Week view: get day from page
                    val dayInfo = getDayInfo(doc)
                    additionalInfo = dayInfo
                    findings.add(SausageResult(restaurantName, restaurantLink, additionalInfo, distance))
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }

        return findings
    }
    
    private fun getDayInfo(doc: Document): String {
        // Try to get the current day from the page title or filter
        val filterText = doc.select("[data-lounaat-filter='day-text']").text()
        if (filterText.isNotEmpty()) {
            return filterText.trim()
        }
        return "Tällä viikolla"
    }
    
    private fun getPageDay(doc: Document): String? {
        val texts = listOf(doc.title(), doc.select("h1").text(), doc.select("h2").text()).joinToString(" ").lowercase()
        for (d in weekDays) {
            if (texts.contains(d)) return d
        }
        return null
    }

    private fun containsSearchWord(text: String): Boolean {
        for (word in searchWords) {
            if (text.contains(word)) return true
        }
        return false
    }

    private fun isSausageToday(text: String, today: String, pageDay: String?): Boolean {
        if (pageDay != null && pageDay == today && containsSearchWord(text)) return true
        if (!text.contains(today)) return true 
        val todayIndex = text.indexOf(today)
        var nextDayIndex = text.length
        for (d in weekDays) {
            val i = text.indexOf(d, todayIndex + 1)
            if (i != -1 && i < nextDayIndex) {
                nextDayIndex = i
            }
        }
        val dayText = text.substring(todayIndex, nextDayIndex)
        return containsSearchWord(dayText)
    }

    private fun getTodayName(): String {
        val calendar = Calendar.getInstance()
        val dayName = calendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale("fi", "FI"))
        return dayName?.lowercase() ?: ""
    }

    private fun showResults(findings: List<SausageResult>, onlyToday: Boolean) {
        val title = if (onlyToday) "Tänään ($currentCity):" else "Löydöt ($currentCity):"
        
        val builder = AlertDialog.Builder(this)
        builder.setTitle(if (findings.isEmpty()) "Ei makkaraa ${maxSearchRadiusKm}km säteellä :(" else "Löytyi! $title")

        if (findings.isEmpty()) {
            builder.setMessage("Ei löytynyt uunimakkaraa tai uunilenkkiä ${maxSearchRadiusKm}km säteellä.")
            builder.setPositiveButton("Voi ei", null)
        } else {
            val adapterItems = findings.map { 
                val distanceStr = if (it.distanceInKm != null) "%.1f km".format(it.distanceInKm) else "Etäisyys tuntematon"
                if (onlyToday) {
                     "${it.restaurant}\nMatka: $distanceStr"
                } else {
                     "${it.additionalInfo}: ${it.restaurant} ($distanceStr)"
                }
            }.toTypedArray()

            builder.setItems(adapterItems) { _, which ->
                val link = findings[which].link
                if (link.isNotEmpty()) {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
                    startActivity(browserIntent)
                }
            }
            builder.setPositiveButton("Sulje", null)
        }
        builder.create().show()
    }
}
