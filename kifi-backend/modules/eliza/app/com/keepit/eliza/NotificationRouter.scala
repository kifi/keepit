package com.keepit.eliza

import com.keepit.model.{User}
import com.keepit.common.db.{Id}

import com.google.inject.{Inject, Singleton, ImplementedBy}

@ImplementedBy(classOf[NotificationRouterImpl])
trait NotificationRouter {
  
  def sendNotification(user: Option[Id[User]], notification: Notification) : Unit

  def onNotification(f: (Option[Id[User]], Notification) => Unit) : Unit
}

@Singleton
class NotificationRouterImpl @Inject() () extends NotificationRouter {

  private var notificationCallbacks = Vector[(Option[Id[User]], Notification) => Unit]()

  def sendNotification(user: Option[Id[User]], notification: Notification) : Unit = {
    notificationCallbacks.par.foreach { f => 
      f(user, notification)
    }
  }

  def onNotification(f: (Option[Id[User]], Notification) => Unit) : Unit = {
    notificationCallbacks = notificationCallbacks :+ f
  }

}
