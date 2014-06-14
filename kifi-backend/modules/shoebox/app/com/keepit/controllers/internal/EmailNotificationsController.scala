package com.keepit.controllers.internal

import com.keepit.common.time.internalTime.DateTimeJsonLongFormat
import com.google.inject.Inject
import com.keepit.common.time.Clock
import com.keepit.scraper.ScraperConfig
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.controller.ShoeboxServiceController
import com.keepit.common.logging.Logging
import play.api.mvc.Action
import com.keepit.commanders.emails.EmailNotificationsCommander
import com.keepit.common.db.Id
import com.keepit.model.{DeepLocator, User}
import com.keepit.eliza.model.ThreadItem
import org.joda.time.DateTime

class EmailNotificationsController @Inject() (
   emailNotificationsCommander: EmailNotificationsCommander
)  (implicit private val clock: Clock,
    private val fortyTwoServices: FortyTwoServices)
  extends ShoeboxServiceController with Logging {

  implicit val userIdFormat = Id.format[User]

  def sendUnreadMessages = Action(parse.tolerantJson) { request =>
    val threadItems = (request.body \ "threadItems").as[Seq[ThreadItem]]
    val otherParticipants = (request.body \ "otherParticipants").as[Seq[Id[User]]]
    val recipientUserId = (request.body \ "userId").as[Id[User]]
    val title = (request.body \ "title").as[String]
    val deepLocator = DeepLocator((request.body \ "deepLocator").as[String])
    val notificationUpdatedAt = (request.body \ "notificationUpdatedAt").asOpt[DateTime]
    emailNotificationsCommander.sendUnreadMessages(threadItems, otherParticipants, recipientUserId, title, deepLocator, notificationUpdatedAt)
    Ok("")
  }

}
