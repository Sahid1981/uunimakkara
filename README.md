# Uunimakkara Tutka 🌭

A Finnish Android application that helps you find restaurants that serve Uunimakkara for lunch near you. 😆

## About This Project

This project is a **learning venture** built largely with AI assistance. The primary goal was not to create a perfect production app, but rather to:

- 🎓 Learn Android app development from the ground up
- 🔤 Master Kotlin programming language
- 💻 Understand what the coding experience feels like
- 🤖 Explore what's possible with AI-assisted development
- 🏗️ Learn proper software architecture and best practices

Throughout this journey, I discovered that AI is an incredible learning tool—it helps you move faster, understand concepts better, and focus on the bigger picture rather than getting stuck on syntax details.

## Features

- **Location-Based Search**: Find restaurants serving sausages within a 30km radius of your current location
- **Weekly & Daily Views**: Search for sausages available today or throughout the week
- **Automatic Web Scraping**: The app navigates lounaat.info website automatically using WebView and JavaScript injection
- **Distance Calculation**: Displays distances to restaurants in kilometers
- **User-Friendly Results**: Clean dialog-based interface showing search results

## Technical Stack

- **Language**: Kotlin
- **UI Framework**: Android (XML layouts)
- **Architecture**: MVVM-inspired with Coroutines for async operations
- **Web Scraping**: Jsoup for HTML parsing
- **Location Services**: Google Play Services for location
- **APIs Used**: 
  - Lounaat.info (web scraping)
  - Android Location Services
  - Android Geocoder

## What I Learned

### Android Development
- Activity lifecycle and UI components
- Permission handling (location, network)
- WebView automation with JavaScript
- Dialog-based user interactions
- Build system and Gradle configuration

### Kotlin Programming
- Coroutines and async/await patterns
- Scoping and extension functions
- Data classes and collections
- Lambda expressions and functional programming
- Null safety features

### Software Engineering
- Code refactoring and DRY principles
- Extracting magic numbers into constants
- Git version control and GitHub workflows
- CI/CD automation with GitHub Actions
- Testing and debugging practices

### Development Tools & Practices
- Android Studio's debugging features
- Gradle wrapper and dependency management
- GitHub Actions for automated builds and releases
- Creating and managing releases

## Project Structure

```
app/src/main/
├── java/com/example/uunimakkata/
│   └── MainActivity.kt          # Main application logic
├── res/
│   ├── layout/
│   │   └── activity_main.xml   # UI layouts
│   └── values/
│       ├── colors.xml
│       ├── strings.xml
│       └── dimens.xml
└── AndroidManifest.xml         # App manifest
```

## How to Build & Run

### Prerequisites
- Android Studio (latest version)
- Java 17 or higher
- Android SDK 24+

### Building
```bash
# Clone the repository
git clone https://github.com/yourusername/uunimakkara.git
cd uunimakkara

# Build the project
./gradlew build

# Install on device/emulator
./gradlew installDebug
```

### Running
1. Install the APK on an Android device or emulator
2. Grant location permissions when prompted
3. Select "Tänään" (Today) or "Viikko" (Week) to search
4. The app will search for sausages and show results sorted by distance

## GitHub Automation

This project includes GitHub Actions that automatically:
- Build a new APK whenever a tag is pushed
- Generate release notes from commit history
- Create GitHub releases with the APK attached

To create a release:
```bash
git tag v1.0.0
git push origin v1.0.0
```

## Key Achievements in This Project

✅ Successfully implemented web page automation using WebView  
✅ Built a complete search and filtering system  
✅ Implemented location services and distance calculation  
✅ Refactored code to follow DRY principles  
✅ Set up automated CI/CD with GitHub Actions  
✅ Translated code to use consistent English naming  
✅ Managed constants properly for maintainability  

## Challenges & Solutions

| Challenge | Solution |
|-----------|----------|
| Web page automation | Used WebView with JavaScript injection |
| Extracting specific data | Parsed HTML with Jsoup CSS selectors |
| Managing async operations | Used Kotlin Coroutines with proper scoping |
| Duplicate code | Extracted common logic into reusable functions |
| Magic numbers scattered | Created class-level constants |

## Future Improvements To Think About

- [ ] Implement proper release build signing
- [ ] Add unit tests
- [ ] Create a backend service instead of web scraping
- [ ] Add user preferences for favorite restaurants
- [ ] Implement caching for better performance
- [ ] Support for multiple languages
- [ ] City Select

## Reflection

Building this app taught me that **coding is not just about syntax**—it's about problem-solving, architecture, and understanding how to break down complex problems into manageable pieces. 

The experience with AI assistance showed me that the future of development isn't about AI replacing developers, but about developers using AI as a tool to accelerate learning and productivity. The important skill is knowing *what* to build and *why*, not just *how* to write the code.

## License

This project is provided as-is for educational purposes.

## Acknowledgments

This project was developed with AI (GitHub Copilot, Gemini In Adroid Studio) assistance as a learning tool. Special thanks to:
- The Android Developer community
- The creators of Lounaat.info
- Kipe for the Idea
- And to all of my friends who tested the app all around Finland 🙂

---

**Happy coding!** 🚀
