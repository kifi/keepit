package com.keepit.slack

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.slick.Database
import com.keepit.heimdal.{ SimpleContextData, HeimdalContext, NonUserEventTypes, HeimdalContextBuilderFactory, NonUserEvent, HeimdalServiceClient }
import com.keepit.model.NotificationCategory
import com.keepit.slack.models.{ SlackChannelName, SlackTeamRepo, SlackChannelId, SlackTeamId }
import com.keepit.social.NonUserKinds

import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class SlackAnalytics @Inject() (
    db: Database,
    slackTeamRepo: SlackTeamRepo,
    slackClient: SlackClientWrapper,
    heimdal: HeimdalServiceClient,
    val heimdalContextBuilder: HeimdalContextBuilderFactory,
    implicit val ec: ExecutionContext) {
  def trackNotificationSent(slackTeamId: SlackTeamId, slackChannelId: SlackChannelId, slackChannelName: SlackChannelName, category: NotificationCategory, existingContext: HeimdalContext = HeimdalContext.empty): Future[Unit] = Future {
    val contextBuilder = heimdalContextBuilder().addExistingContext(existingContext)

    val teamNameFut = {
      if (existingContext.get[String]("slackTeamName").isEmpty) {
        db.readOnlyReplicaAsync { implicit s =>
          slackTeamRepo.getBySlackTeamId(slackTeamId).foreach { slackTeam =>
            contextBuilder += ("slackTeamName", slackTeam.slackTeamName.value)
          }
        }
      } else Future.successful(())
    }
    val numMembersFut = {
      if (existingContext.get[Double]("numChannelMembers").isEmpty) {
        slackClient.getChannelInfo(slackTeamId, slackChannelId).map { info =>
          contextBuilder += ("numChannelMembers", info.numMembers)
        }
      } else Future.successful(())
    }

    contextBuilder += ("category", category.category)
    contextBuilder += ("channel", "slack")
    contextBuilder += ("action", "delivered")
    contextBuilder += ("slackTeamId", slackTeamId.value)
    contextBuilder += ("slackChannelId", slackChannelId.value)
    contextBuilder += ("slackChannelName", slackChannelName.value) // this could be fetched with slackClient.getChannelInfo, but as of yet all callers have it already
    val nonUserId = s"${slackTeamId.value}:${slackChannelId.value}"

    for {
      _ <- teamNameFut
      _ <- numMembersFut
    } heimdal.trackEvent(NonUserEvent(nonUserId, NonUserKinds.slack, contextBuilder.build, NonUserEventTypes.WAS_NOTIFIED))
  }

  def trackNotificationClicked(): Future[Unit] = Future {

  }
}
