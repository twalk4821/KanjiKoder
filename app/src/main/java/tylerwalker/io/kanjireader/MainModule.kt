package tylerwalker.io.kanjireader

import io.reactivex.Flowable
import io.reactivex.processors.PublishProcessor

// navigation events
sealed class NavigationEvent {
    object Main: NavigationEvent()
    object Dictionary: NavigationEvent()
}

val navigationProcessor = PublishProcessor.create<NavigationEvent>().toSerialized()
val navigationFlowable = navigationProcessor as Flowable<NavigationEvent>

// decode settings
// whether to decode black (Normal) or white (Inverted) writing
sealed class DecodeMode {
    object Normal: DecodeMode()
    object Inverted: DecodeMode()
}

sealed class UIEvent {
    object ShowDecodeHelp: UIEvent()
}

val uiEventProcessor = PublishProcessor.create<UIEvent>().toSerialized()
val uiEventFlowable = uiEventProcessor as Flowable<UIEvent>