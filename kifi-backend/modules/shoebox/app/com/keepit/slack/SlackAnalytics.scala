package com.keepit.slack

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.crypto.KifiUrlRedirectHelper
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ Param, Query }
import com.keepit.heimdal.{ HeimdalContext, NonUserEventTypes, HeimdalContextBuilderFactory, NonUserEvent, HeimdalServiceClient }
import com.keepit.model.NotificationCategory
import com.keepit.slack.models._
import com.keepit.social.NonUserKinds
import com.keepit.common.core._

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
    implicit val ec: ExecutionContext) extends Logging {
  private def trackSlackNotificationEvent(slackTeamId: SlackTeamId, slackChannelId: SlackChannelId, category: NotificationCategory, existingContext: HeimdalContext = HeimdalContext.empty): Future[Unit] = {
    val contextBuilder = heimdalContextBuilder().addExistingContext(existingContext)

    val teamFut = {
      if (existingContext.get[String]("slackTeamName").isEmpty) {
        db.readOnlyReplicaAsync { implicit s =>
          slackTeamRepo.getBySlackTeamId(slackTeamId)
        }
      } else Future.successful(None)
    }
    val channelFut = {
      if (existingContext.get[Double]("numChannelMembers").isEmpty || existingContext.get[String]("slackChannelName").isEmpty) {
        slackChannelId match {
          case publicChannelId: SlackChannelId.Public => slackClient.getPublicChannelInfo(slackTeamId, publicChannelId).imap(Some(_))
          case privateChannelId: SlackChannelId.Private => slackClient.getPrivateChannelInfo(slackTeamId, privateChannelId).imap(Some(_))
          case _ => Future.successful(None)
        }
      } else Future.successful(None)
    }

    for {
      teamOpt <- teamFut.recover { case _ => None }
      channelOpt <- channelFut.recover { case _ => None }
    } yield {
      teamOpt.foreach { team =>
        contextBuilder += ("slackTeamName", team.slackTeamName.value)
      }

      channelOpt.foreach { info =>
        contextBuilder += ("numChannelMembers", info.members.size)
        contextBuilder += ("slackChannelName", info.channelName.value)
      }

      contextBuilder += ("category", category.category)
      contextBuilder += ("channel", "slack")
      contextBuilder += ("slackTeamId", slackTeamId.value)
      contextBuilder += ("slackChannelId", slackChannelId.value)
      log.info(s"[clickTracking] processed slack $category, $slackTeamId, $slackChannelId, sending to heimdal")
      val nonUserIdentifier = s"${slackTeamId.value}:${slackChannelId.value}"
      heimdal.trackEvent(NonUserEvent(nonUserIdentifier, NonUserKinds.slack, contextBuilder.build, NonUserEventTypes.WAS_NOTIFIED))
    }
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
