package com.keepit.notify.info

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.path.EncodedPath
import com.keepit.model.{ User, Library }
import com.keepit.notify.model.NotificationEvent
import com.keepit.shoebox.ShoeboxServiceClient

import scala.concurrent.{ ExecutionContext, Future }

class ElizaNotificationInfoSourceImpl @Inject() (
    shoeboxServiceClient: ShoeboxServiceClient,
    implicit val executionContext: ExecutionContext) extends NotificationInfoSource {

  override def userImage(id: Id[User]): Future[String] = Future.failed(new UnsupportedOperationException)

  override def libraryPath(id: Id[Library]): Future[EncodedPath] = Future.failed(new UnsupportedOperationException)

  override def user(id: Id[User]): Future[User] = shoeboxServiceClient.getUser(id).map(_.get)

  override def library(id: Id[Library]): Future[Library] = Future.failed(new UnsupportedOperationException)

}
