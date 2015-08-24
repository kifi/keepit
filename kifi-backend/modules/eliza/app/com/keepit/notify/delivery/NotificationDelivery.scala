package com.keepit.notify.delivery

import com.keepit.eliza.model.NotificationItem
import com.keepit.notify.model.Recipient

import scala.concurrent.{ ExecutionContext, Future }

trait NotificationDelivery {

  def deliver(recipient: Recipient, items: Set[NotificationItem])(implicit ec: ExecutionContext): Future[Unit]

}

object NotificationDelivery {

  def both(first: NotificationDelivery, second: NotificationDelivery) = new NotificationDelivery {
    override def deliver(recipient: Recipient, items: Set[NotificationItem])(implicit ec: ExecutionContext): Future[Unit] = {
      val f1 = first.deliver(recipient, items)
      val f2 = second.deliver(recipient, items)
      for {
        _ <- f1
        _ <- f2
      } yield ()
    }
  }

}
