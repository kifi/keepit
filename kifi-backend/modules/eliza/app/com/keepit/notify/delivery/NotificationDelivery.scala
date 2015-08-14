package com.keepit.notify.delivery

import com.keepit.notify.model.NotificationEvent

import scala.concurrent.{ ExecutionContext, Future }

trait NotificationDelivery {

  def deliver(events: Set[NotificationEvent])(implicit ec: ExecutionContext): Future[Unit]

}

object NotificationDelivery {

  def both(first: NotificationDelivery, second: NotificationDelivery) = new NotificationDelivery {
    override def deliver(events: Set[NotificationEvent])(implicit ec: ExecutionContext): Future[Unit] = {
      val f1 = first.deliver(events)
      val f2 = second.deliver(events)
      for {
        _ <- f1
        _ <- f2
      } yield ()
    }
  }

}
