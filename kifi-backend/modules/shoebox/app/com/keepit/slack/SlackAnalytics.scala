package com.keepit.slack

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.crypto.KifiUrlRedirectHelper
import com.keepit.common.db.slick.Database
import com.keepit.common.net.{ Param, Query }
import com.keepit.heimdal.{ HeimdalContext, NonUserEventTypes, HeimdalContextBuilderFactory, NonUserEvent, HeimdalServiceClient }
import com.keepit.model.NotificationCategory
import com.keepit.slack.models._
import com.keepit.social.NonUserKinds

import scala.concurrent.{ ExecutionContext, Future }

object SlackAnalytics {
  def generateTrackingParams(slackChannelId: SlackChannelId, category: NotificationCategory, subaction: Option[String] = None): Query = {
    val queryParams = Query("slackChannelId" -> Some(slackChannelId.value), "category" -> Some(category.category), "subaction" -> subaction)
    Query(Param("t", Some(KifiUrlRedirectHelper.signTrackingParams(queryParams, Some("ascii")))))
  }
}

@Singleton
class SlackAnalytics @Inject() (
    db: Database,
    slackTeamRepo: SlackTeamRepo,
    slackClient: SlackClientWrapper,
    heimdal: HeimdalServiceClient,
    val heimdalContextBuilder: HeimdalContextBuilderFactory,
    implicit val ec: ExecutionContext) {
  private def trackSlackNotificationEvent(slackTeamId: SlackTeamId, slackChannelId: SlackChannelId, category: NotificationCategory, existingContext: HeimdalContext = HeimdalContext.empty): Future[Unit] = {
    val contextBuilder = heimdalContextBuilder().addExistingContext(existingContext)

    val teamFut = {
      if (existingContext.get[String]("slackTeamName").isEmpty) {
        db.readOnlyReplicaAsync { implicit s =>
          slackTeamRepo.getBySlackTeamId(slackTeamId).foreach { team =>
            contextBuilder += ("slackTeamName", team.slackTeamName.value)
          }
        }
      } else Future.successful(())
    }
    val membersFut = {
      if (existingContext.get[Double]("numChannelMembers").isEmpty || existingContext.get[String]("slackChannelName").isEmpty) {
        val channelInfoFuture = slackChannelId match {
          case publicChannelId: SlackChannelId.Public => slackClient.getPublicChannelInfo(slackTeamId, publicChannelId)
          case privateChannelId: SlackChannelId.Private => slackClient.getPrivateChannelInfo(slackTeamId, privateChannelId)
          case _ => Future.failed(new UnsupportedOperationException(s"Cannot get information for Slack channel $slackChannelId"))
        }
        channelInfoFuture map { info =>
          contextBuilder += ("numChannelMembers", info.members.size)
          contextBuilder += ("slackChannelName", info.channelName.value)
          ()
        }
      } else Future.successful(())
    }

    contextBuilder += ("category", category.category)
    contextBuilder += ("channel", "slack")
    contextBuilder += ("slackTeamId", slackTeamId.value)
    contextBuilder += ("slackChannelId", slackChannelId.value)
    val nonUserIdentifier = s"${slackTeamId.value}:${slackChannelId.value}"

    for {
      _ <- teamFut
      _ <- membersFut
    } yield heimdal.trackEvent(NonUserEvent(nonUserIdentifier, NonUserKinds.slack, contextBuilder.build, NonUserEventTypes.WAS_NOTIFIED))
  }

  def trackNotificationSent(slackTeamId: SlackTeamId, slackChannelId: SlackChannelId, slackChannelName: SlackChannelName, category: NotificationCategory, existingContext: HeimdalContext = HeimdalContext.empty): Future[Unit] = Future {
    val contextBuilder = heimdalContextBuilder().addExistingContext(existingContext)
    contextBuilder += ("action", "delivered")
    contextBuilder += ("slackChannelName", slackChannelName.value)
    trackSlackNotificationEvent(slackTeamId, slackChannelId, category, existingContext)
  }

  def trackNotificationClicked(slackTeamId: SlackTeamId, slackChannelId: SlackChannelId, category: NotificationCategory, subaction: String): Future[Unit] = Future {
    val contextBuilder = heimdalContextBuilder()
    contextBuilder += ("action", "click")
    contextBuilder += ("subaction", subaction)
    trackSlackNotificationEvent(slackTeamId, slackChannelId, category)
  }
}
