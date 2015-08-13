package com.keepit.notify.info

import com.keepit.common.db.Id
import com.keepit.common.path.EncodedPath
import com.keepit.model.{ Library, User }
import com.keepit.notify.model.NotificationEvent

import scala.concurrent.Future
import scala.util.{ Failure, Try }

class ShoeboxNotificationInfoSourceImpl extends NotificationInfoSource {

  override def pickOne[E <: NotificationEvent](events: Set[E]): Future[E] = Future.fromTry(Try { events.head })

  override def userImage(id: Id[User]): Future[String] = ???

  override def libraryPath(id: Id[Library]): Future[EncodedPath] = ???

  override def user(id: Id[User]): Future[User] = ???

  override def library(id: Id[Library]): Future[Library] = ???

}
