package com.keepit.notify.info

import com.keepit.common.db.Id
import com.keepit.common.path.EncodedPath
import com.keepit.model.{ Library, User }
import com.keepit.notify.model.NotificationEvent

import scala.concurrent.Future

trait NotificationInfoSource {

  def pickOne[E <: NotificationEvent](events: Set[E]): Future[E]

  def user(id: Id[User]): Future[User]

  def library(id: Id[Library]): Future[Library]

  def userImage(id: Id[User]): Future[String]

  def libraryPath(id: Id[Library]): Future[EncodedPath]

}
