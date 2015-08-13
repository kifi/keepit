package com.keepit.notify.action

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.Id
import com.keepit.eliza.commanders.NotificationCommander
import com.keepit.eliza.model.{ NotificationItemRepo, Notification }
import com.keepit.model.{User, Library}
import com.keepit.notify.info._
import com.keepit.shoebox.ShoeboxServiceClient

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class NotificationInfoGenerator @Inject() (
    shoeboxServiceClient: ShoeboxServiceClient,
    notificationCommander: NotificationCommander,
    implicit val executionContext: ExecutionContext) {

  def run[A](that: ReturnsInfo[A]): Future[A] = that match {
    case Returns(a) => Future.successful(a)

    case Fails(e) => Future.failed(e)

    case AndThen(previous, f) => run(previous).map(f)

    case GetUser(id: Id[User]) =>
      val ev = implicitly[User <:< A]
      shoeboxServiceClient.getUser(id).flatMap {
        case Some(user) => Future.successful(ev(user))
        case None => Future.failed(new NoSuchElementException(s"User with id $id did not exist"))
      }

    case GetLibrary(id: Id[Library]) => shoeboxServiceClient.getBasicLibraryDetails()
  }

}
