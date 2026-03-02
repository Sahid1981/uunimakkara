package com.example.uunimakkata
import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
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
import androidx.core.net.toUri
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.Calendar
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

data class SausageResult(
    val restaurant: String,
    val link: String,
    val additionalInfo: String, 
    val distanceInKm: Double? = null
)

data class SearchDay(
    val hash: String,
    val label: String
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
    private val weekFindingKeys = mutableSetOf<String>()
    private var currentDayIndex = 0
    // Number to differentiate days in the URL hash
    private var daysToSearch = listOf<SearchDay>() // Will be populated dynamically

    @SuppressLint("SetTextI18n")
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            ) {
                updateLocation()
            } else {
                tvLocation.text = "Sijaintilupa evätty. Käytetään oletuksena $currentCity"
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

    private fun generateDaysToSearch(): List<SearchDay> {
        // Map weekday constants to hash values used in the website
        val hashMap = mapOf(
            Calendar.MONDAY to "8",
            Calendar.TUESDAY to "9",
            Calendar.WEDNESDAY to "3",
            Calendar.THURSDAY to "4",
            Calendar.FRIDAY to "5",
            Calendar.SATURDAY to "6",
            Calendar.SUNDAY to "7"
        )
        
        val calendar = Calendar.getInstance()
        val days = mutableListOf<SearchDay>()
        
        android.util.Log.d("UuniSearch", "Today is ${getTodayName()}")
        
        // Get today + next 5 days
        for (i in 0..5) {
            val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
            val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
            val monthOfYear = calendar.get(Calendar.MONTH)
            val hash = hashMap[dayOfWeek]
            
            val dayName = calendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale("fi", "FI"))
            val fullDate = "$dayName ${dayOfMonth}.${monthOfYear + 1}."
            
            if (hash != null) {
                days.add(SearchDay(hash = hash, label = fullDate))
                android.util.Log.d("UuniSearch", "Day $i: $fullDate (hash #$hash)")
            }
            
            calendar.add(Calendar.DAY_OF_MONTH, 1)
        }
        
        return days
    }

    private fun startSearch(onlyToday: Boolean) {
        searchOnlyToday = onlyToday
        
        loadingDialog = AlertDialog.Builder(this)
            .setTitle("Uunimakkara Tutka")
            .setMessage("Ladataan sivua ja painellaan nappeja (tämä kestää hetken)...")
            .setCancelable(false)
            .create()
        loadingDialog?.show()

        var searchCity = currentCity.lowercase().replace("ä", "a").replace("ö", "o").replace("å", "a")
        val url = "https://www.lounaat.info/$searchCity"
        
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
                // Week view: iterate through 7 days
                weekFindings.clear()
                weekFindingKeys.clear()
                currentDayIndex = 0

                val availableDays = fetchDaysToSearchFromPage()
                daysToSearch = if (availableDays.isNotEmpty()) {
                    availableDays
                } else {
                    generateDaysToSearch()
                }

                android.util.Log.d("UuniSearch", "Week search days loaded from page: ${daysToSearch.size}")
                daysToSearch.forEachIndexed { index, day ->
                    android.util.Log.d("UuniSearch", "  [$index] ${day.label} (#${day.hash})")
                }

                if (daysToSearch.isEmpty()) {
                    loadingDialog?.dismiss()
                    showResults(emptyList(), false)
                    return@launch
                }

                loadNextDay()
            }
        }
    }

    private suspend fun fetchDaysToSearchFromPage(): List<SearchDay> = suspendCancellableCoroutine { continuation ->
        val js = """
            (function() {
                var links = document.querySelectorAll('.dayview-filter a');
                var out = [];
                for (var i = 0; i < links.length; i++) {
                    var href = links[i].getAttribute('href') || '';
                    var text = (links[i].textContent || '').trim();
                    out.push(href + '@@' + text);
                }
                return out.join('||');
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { rawResult ->
            try {
                val cleaned = rawResult
                    ?.removePrefix("\"")
                    ?.removeSuffix("\"")
                    ?.replace("\\\"", "\"")
                    ?.replace("\\n", "")
                    ?.replace("\\u003C", "<")
                    ?.replace("\\u003E", ">")
                    ?.trim()
                    ?: ""

                val regex = Regex("#(\\d+)")
                val parsed = mutableListOf<SearchDay>()

                if (cleaned.isNotEmpty() && cleaned != "null") {
                    cleaned.split("||").forEach { item ->
                        val parts = item.split("@@")
                        if (parts.size >= 2) {
                            val href = parts[0]
                            val label = parts[1].trim()
                            val hash = regex.find(href)?.groupValues?.getOrNull(1)
                            if (!hash.isNullOrEmpty() && label.isNotEmpty()) {
                                parsed.add(SearchDay(hash = hash, label = label))
                            }
                        }
                    }
                }

                continuation.resume(parsed)
            } catch (e: Exception) {
                android.util.Log.d("UuniSearch", "Failed to parse days from page: ${e.message}")
                continuation.resume(emptyList())
            }
        }
    }
    
    private suspend fun scrollAndClickShowMore() {
        delay(1200)

        var rounds = 0
        var stagnantRounds = 0
        while (rounds < 8) {
            val countBefore = getMenuCardCount()
            webView.evaluateJavascript("window.scrollTo(0, document.body.scrollHeight);", null)
            delay(900)

            val clicked = clickShowMoreButtonOnce()
            if (!clicked) {
                android.util.Log.d("UuniSearch", "Show more button not found on round $rounds")
                break
            }

            android.util.Log.d("UuniSearch", "Show more clicked on round $rounds")
            delay(2200)

            val countAfter = getMenuCardCount()
            if (countAfter <= countBefore) {
                stagnantRounds++
                android.util.Log.d("UuniSearch", "No new cards loaded ($countBefore -> $countAfter), stagnant rounds=$stagnantRounds")
            } else {
                stagnantRounds = 0
                android.util.Log.d("UuniSearch", "New cards loaded ($countBefore -> $countAfter)")
            }

            if (stagnantRounds >= 2) {
                android.util.Log.d("UuniSearch", "Stopping show more clicks because no new cards appeared")
                break
            }

            rounds++
        }

        delay(800)
    }

    private suspend fun getMenuCardCount(): Int = suspendCancellableCoroutine { continuation ->
        val countJs = """
            (function() {
                var c1 = document.querySelectorAll('div.menu.item').length;
                var c2 = document.querySelectorAll('div.item').length;
                return c1 > 0 ? c1 : c2;
            })();
        """.trimIndent()

        webView.evaluateJavascript(countJs) { rawResult ->
            val count = rawResult?.replace("\"", "")?.trim()?.toIntOrNull() ?: 0
            continuation.resume(count)
        }
    }

    private suspend fun clickShowMoreButtonOnce(): Boolean = suspendCancellableCoroutine { continuation ->
        val clickJs = """
            (function() {
                function clickElement(el) {
                    if (!el) return false;
                    try {
                        el.scrollIntoView({ behavior: 'instant', block: 'center' });
                    } catch (e) {}
                    try {
                        el.click();
                    } catch (e) {}
                    try {
                        el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
                    } catch (e) {}
                    return true;
                }

                var selectors = [
                    '.button.showmore',
                    '.showmore',
                    'button.showmore',
                    'a.showmore',
                    '[data-action="showmore"]',
                    '.button[data-action="showmore"]'
                ];

                for (var i = 0; i < selectors.length; i++) {
                    var el = document.querySelector(selectors[i]);
                    if (clickElement(el)) return true;
                }

                var candidates = document.querySelectorAll('button, a, div, span');
                for (var j = 0; j < candidates.length; j++) {
                    var t = (candidates[j].textContent || '').trim().toLowerCase();
                    if (t.indexOf('näytä lisää') !== -1 || t.indexOf('nayta lisaa') !== -1) {
                        if (clickElement(candidates[j])) return true;
                    }
                }

                return false;
            })();
        """.trimIndent()

        webView.evaluateJavascript(clickJs) { rawResult ->
            val clicked = rawResult?.contains("true", ignoreCase = true) == true
            continuation.resume(clicked)
        }
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
            val searchDay = daysToSearch[currentDayIndex]
            val dayHash = searchDay.hash
            delay(1000)

            val clickDayJs = """
                (function() {
                    var targetHash = '#$dayHash';
                    var links = document.querySelectorAll('.dayview-filter a');
                    var clicked = false;

                    for (var i = 0; i < links.length; i++) {
                        var href = links[i].getAttribute('href') || '';
                        if (href.indexOf(targetHash) !== -1) {
                            links[i].click();
                            clicked = true;
                            break;
                        }
                    }

                    if (!clicked) {
                        window.location.hash = targetHash;
                    }

                    return clicked ? 'clicked:' + targetHash : 'hash-only:' + targetHash;
                })();
            """.trimIndent()

            webView.evaluateJavascript(clickDayJs) { result ->
                android.util.Log.d(
                    "UuniSearch",
                    "Navigate day $currentDayIndex/${daysToSearch.size - 1} -> ${searchDay.label} (#$dayHash), mode=$result"
                )
            }

            // Wait for page to update after selecting target hash
            delay(3500)
            
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
                val expectedDay = daysToSearch.getOrNull(currentDayIndex)?.label ?: "Päivä ${currentDayIndex + 1}"
                val detectedDay = getDayInfo(doc)
                val dayResults = analyzeDayDocument(doc, checkToday = false, forcedDayInfo = expectedDay)
                
                android.util.Log.d(
                    "UuniSearch",
                    "Day $currentDayIndex expected=$expectedDay, detected=$detectedDay: Found ${dayResults.size} results"
                )
                dayResults.forEach {
                    android.util.Log.d("UuniSearch", "  - ${it.restaurant}: ${it.additionalInfo}")
                }
                
                for (result in dayResults) {
                    val keyBase = if (result.link.isNotBlank()) result.link else result.restaurant.lowercase()
                    val key = "${result.additionalInfo}|$keyBase"
                    if (weekFindingKeys.add(key)) {
                        weekFindings.add(result)
                    } else {
                        android.util.Log.d("UuniSearch", "  Duplicate skipped: ${result.additionalInfo} - ${result.restaurant}")
                    }
                }
                
                android.util.Log.d("UuniSearch", "Total findings so far: ${weekFindings.size}")
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
                tvLocation.text =
                    "Paikannus aikakatkaistiin. Tarkista GPS.\n Käytetään $currentCity"
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
    
    private fun analyzeDayDocument(doc: Document, checkToday: Boolean = false, forcedDayInfo: String? = null): List<SausageResult> {
        val findings = mutableListOf<SausageResult>()
        val geocoder = Geocoder(this@MainActivity, Locale("fi", "FI")) 

        try {
            val pageDay = getPageDay(doc)
            
            val items = doc.select("div.menu.item") 
            val candidates = if (items.isEmpty()) doc.select("div.item") else items
            
            // Debug logging for week search
            if (!checkToday) {
                android.util.Log.d("UuniSearch", "Week search - Found ${candidates.size} candidates with selector ${if (items.isNotEmpty()) "div.menu.item" else "div.item"}")
                android.util.Log.d("UuniSearch", "Day info: ${getDayInfo(doc)}")
            }
            
            val todayName = getTodayName() 

            for (item in candidates) {
                val itemText = item.text().lowercase()
                
                if (!containsSearchWord(itemText)) {
                    if (!checkToday && findings.isEmpty()) {
                        android.util.Log.d("UuniSearch", "Item filtered out (no search word): ${itemText.take(50)}")
                    }
                    continue
                }

                val restaurantElement = item.select("h3 a")
                if (restaurantElement.isEmpty()) {
                    android.util.Log.d("UuniSearch", "Item has search word but no h3 a element: ${itemText.take(50)}")
                    continue
                }
                
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

                var additionalInfo: String

                if (checkToday) {
                    if (isSausageToday(itemText, todayName, pageDay)) {
                        additionalInfo = "Mahdollisesti tänään"
                        findings.add(SausageResult(restaurantName, restaurantLink, additionalInfo, distance))
                        android.util.Log.d("UuniSearch", "Today: Added $restaurantName")
                    }
                } else {
                    // Week view: get day from page
                    val dayInfo = forcedDayInfo ?: getDayInfo(doc)
                    additionalInfo = dayInfo
                    findings.add(SausageResult(restaurantName, restaurantLink, additionalInfo, distance))
                    if (findings.size <= 3) {
                        android.util.Log.d("UuniSearch", "Week: Added $restaurantName for $dayInfo")
                    }
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }

        return findings
    }
    
    private fun getDayInfo(doc: Document): String {
        // Try multiple ways to get the current day from the page
        
        // 1. Try data-lounaat-filter
        val filterText = doc.select("[data-lounaat-filter='day-text']").text()
        if (filterText.isNotEmpty()) {
            android.util.Log.d("UuniSearch", "Day found from filter: $filterText")
            return filterText.trim()
        }
        
        // 2. Try page title
        val title = doc.title().lowercase()
        for (day in weekDays) {
            if (title.contains(day.trim())) {
                android.util.Log.d("UuniSearch", "Day found from title: $day")
                return day.trim()
            }
        }
        
        // 3. Try h1 headers
        val h1Text = doc.select("h1").text().lowercase()
        for (day in weekDays) {
            if (h1Text.contains(day.trim())) {
                android.util.Log.d("UuniSearch", "Day found from h1: $day")
                return day.trim()
            }
        }
        
        // 4. Try h2 headers
        val h2Text = doc.select("h2").text().lowercase()
        for (day in weekDays) {
            if (h2Text.contains(day.trim())) {
                android.util.Log.d("UuniSearch", "Day found from h2: $day")
                return day.trim()
            }
        }
        
        // 5. Try to find day in active filter links
        val activeFilter = doc.select(".dayview-filter a.active").text().lowercase()
        for (day in weekDays) {
            if (activeFilter.contains(day.trim())) {
                android.util.Log.d("UuniSearch", "Day found from active filter: $day")
                return day.trim()
            }
        }
        
        android.util.Log.d("UuniSearch", "Day not found, using default")
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
                    val browserIntent = Intent(Intent.ACTION_VIEW, link.toUri())
                    startActivity(browserIntent)
                }
            }
            builder.setPositiveButton("Sulje", null)
        }
        builder.create().show()
    }
}
