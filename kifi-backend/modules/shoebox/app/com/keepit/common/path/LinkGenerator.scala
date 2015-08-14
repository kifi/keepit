package com.keepit.common.path

import com.google.inject.{ Singleton, Injector, Inject }
import com.keepit.commanders.PathCommander
import com.keepit.common.db.slick.{ Database, DataBaseComponent }
import com.keepit.model.{ LibraryRepo, User, UserRepo }
import com.keepit.notify.model.{ NotificationEvent, NotificationKind }

abstract class LinkGenerator[N <: NotificationEvent] {

  def getLink(actions: Set[N]): Path

}
