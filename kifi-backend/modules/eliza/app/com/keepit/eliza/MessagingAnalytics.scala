package com.keepit.eliza

import com.google.inject.{Singleton, Inject}
import com.keepit.heimdal._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time._
import com.keepit.common.akka.SafeFuture
import com.keepit.model.User
import com.keepit.common.db.Id
import play.api.libs.concurrent.Execution.Implicits.defaultContext

@Singleton
class MessagingAnalytics @Inject() (
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  heimdal: HeimdalServiceClient,
  airbrake: AirbrakeNotifier) {

  def sentNotificationForMessage(userId: Id[User], message: Message, thread: MessageThread, muted: Boolean): Unit = {
    val sentAt = currentDateTime
    SafeFuture {
      val contextBuilder = heimdalContextBuilder()
      contextBuilder += ("action", "sent")
      contextBuilder += ("channel", "kifi")
      contextBuilder += ("global", false)
      contextBuilder += ("muted", muted)
      contextBuilder += ("messageExternalId", message.externalId.id)
      contextBuilder += ("threadId", thread.id.get.id)
      heimdal.trackEvent(UserEvent(userId.id, contextBuilder.build, UserEventTypes.WAS_NOTIFIED, sentAt))
    }
  }

  def clearedNotificationForMessage(userId: Id[User], message: Message, thread: MessageThread, existingContext: HeimdalContext): Unit = {
    val clearedAt = currentDateTime
    SafeFuture {
      val contextBuilder = new HeimdalContextBuilder
      contextBuilder.data ++ existingContext.data
      contextBuilder += ("action", "cleared")
      contextBuilder += ("channel", "kifi")
      contextBuilder += ("messageExternalId", message.externalId.id)
      contextBuilder += ("threadId", thread.id.get.id)
      heimdal.trackEvent(UserEvent(userId.id, contextBuilder.build, UserEventTypes.WAS_NOTIFIED, clearedAt))
    }
  }
}
