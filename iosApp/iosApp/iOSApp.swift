import SwiftUI

/**
 * iOS-обёртка вокруг общего Compose UI.
 *
 * Здесь нет ни экранов, ни состояния: `ContentView` поднимает один Compose-контроллер
 * из `:sample`, и всё дальнейшее — Kotlin.
 */
@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
