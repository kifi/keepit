package com.keepit.model.tracking

import com.keepit.common.db._
import com.keepit.common.net.UserAgent
import com.keepit.common.time._
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.{ User, Library }
import org.joda.time.DateTime

case class LibraryViewTracking(
    id: Option[Id[LibraryViewTracking]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    ownerId: Id[User],
    viewerId: Option[Id[User]],
    libraryId: Id[Library],
    source: Option[LibraryViewSource],
    state: State[LibraryViewTracking] = LibraryViewTrackingStates.ACTIVE) extends ModelWithState[LibraryViewTracking] {

  def withId(id: Id[LibraryViewTracking]): LibraryViewTracking = copy(id = Some(id))
  def withUpdateTime(time: DateTime): LibraryViewTracking = copy(updatedAt = time)
}

object LibraryViewTrackingStates extends States[LibraryViewTracking]

case class LibraryViewSource(value: String)

object LibraryViewSource {
  val SITE = LibraryViewSource("site")
  val iOS = LibraryViewSource("ios")
  val ANDROID = LibraryViewSource("android")

  def fromContext(context: HeimdalContext): Option[LibraryViewSource] = {
    context.get[String]("userAgent").flatMap { ua =>
      val parsed = UserAgent(ua)
      if (parsed.isAndroid || parsed.isKifiAndroidApp) {
        Some(ANDROID)
      } else if (parsed.isIphone || parsed.isKifiIphoneApp) {
        Some(iOS)
      } else {
        if (parsed.possiblyBot) None else Some(SITE)
      }
    }
  }
}
