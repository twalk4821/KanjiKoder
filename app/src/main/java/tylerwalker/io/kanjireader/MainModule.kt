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