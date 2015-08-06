package com.keepit.common.path

import com.google.inject.{ Singleton, Injector, Inject }
import com.keepit.commanders.PathCommander
import com.keepit.common.db.slick.{ Database, DataBaseComponent }
import com.keepit.model.{ User, UserRepo }
import com.keepit.notify.model.{ NewFollower, NotificationEvent, NotificationKind }

abstract class LinkGenerator[N <: NotificationEvent] {

  def getLink(actions: Set[N]): Path

}

@Singleton
class NewFollowerLinkGenerator @Inject() (
    libraryPathCommander: PathCommander) extends LinkGenerator[NewFollower] {

  override def getLink(actions: Set[NewFollower]): Path = libraryPathCommander.pathForLibrary(actions.head.library)

}
