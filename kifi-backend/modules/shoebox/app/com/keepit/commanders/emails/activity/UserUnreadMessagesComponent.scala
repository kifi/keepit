package com.keepit.commanders.emails.activity

import com.keepit.common.db.Id
import com.keepit.common.mail.template.helpers._
import com.keepit.common.time._
import com.keepit.eliza.model.{ MessageSenderNonUserView, MessageSenderUserView, MessageSenderView, MessageView, UserThreadView }
import com.keepit.model.User
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.twirl.api.Html

import scala.concurrent.Future

// TODO (josh) much of the functionality below may not be necessary per the latest design, remove unused code after design is final
case class EmailUnreadThreadView(private val view: UserThreadView) {
  val pageTitle = view.pageTitle
  val lastSeen = view.lastSeen
  val lastActive = view.notificationUpdatedAt
  val allMessages = view.messages

  val messageSendersToShow: Seq[Html] = {
    val senders = view.messages collect {
      case MessageView(MessageSenderUserView(id), _, _) => firstName(id)
      case MessageView(MessageSenderNonUserView(identifier), _, _) => safeHtml(identifier)
    }

    val others = senders.size - view.messages.size
    if (others > 0) senders :+ safeHtml(s"$others others")
    else senders
  }

  val firstMessageSentTime: String = {
    val msgCreatedAtPT = allMessages.head.createdAt.withZone(zones.PT)
    val nowPT = currentDateTime(zones.PT)
    val startOfTodayPT = nowPT.withTimeAtStartOfDay()
    if (startOfTodayPT < msgCreatedAtPT) "today"
    else if (startOfTodayPT.minusDays(1) < msgCreatedAtPT) "yesterday"
    else if (startOfTodayPT.minusDays(6) < msgCreatedAtPT) msgCreatedAtPT.dayOfWeek().getAsText
    else if (msgCreatedAtPT.minusDays(14) < msgCreatedAtPT) "last week"
    else s"on ${msgCreatedAtPT.monthOfYear().getAsText} ${msgCreatedAtPT.dayOfMonth().getAsText}"
  }

  val userMessages = view.messages filter {
    case MessageView(MessageSenderUserView(_), _, _) => true
    case _ => false
  }
  val totalMessageCount = view.messages.size
  val otherMessageCount = totalMessageCount - messageSendersToShow.size
}

class UserUnreadMessagesComponent(val toUserId: Id[User]) extends ActivityEmailHelpers {
  this: ActivityEmailDependencies =>
  override val previouslySent = Seq.empty

  def minAgeOfUnreadNotificationThreads = emailPrepTime.minusWeeks(6)

  def maxAgeOfUnreadNotificationThreads = emailPrepTime.minusHours(12)

  val maxUnreadNotificationThreads = 10

  def apply(): Future[Seq[EmailUnreadThreadView]] =
    eliza.getUnreadNotifications(toUserId, maxUnreadNotificationThreads) map { threads =>
      threads.filter { thread =>
        val threadLastActive = thread.notificationUpdatedAt
        threadLastActive > minAgeOfUnreadNotificationThreads &&
          threadLastActive < maxAgeOfUnreadNotificationThreads &&
          thread.messages.nonEmpty && thread.messages.headOption.exists(_.from.kind != MessageSenderView.SYSTEM)
      } take maxUnreadNotificationThreads map EmailUnreadThreadView
    } recover {
      case e: Exception =>
        airbrake.notify(s"failed to fetch unread messages for $toUserId", e)
        Seq.empty
    }
}

class UserUnreadMessageCountComponent(val toUserId: Id[User]) extends ActivityEmailHelpers { this: ActivityEmailDependencies =>
  override val previouslySent = Seq.empty

  def apply(): Future[Int] = {
    eliza.getUserThreadStats(toUserId) map (_.all)
  }
}
