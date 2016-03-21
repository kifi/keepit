package com.keepit.shoebox.controllers

import com.keepit.common.controller.{ MaybeUserRequest, UserActions }
import com.keepit.common.crypto.KifiUrlRedirectHelper
import com.keepit.model.NotificationCategory
import com.keepit.slack.SlackAnalytics
import com.keepit.slack.models.{ SlackChannelId, SlackTeamId }
import play.api.mvc.{ Result, ActionFunction, Controller }

import scala.concurrent.{ ExecutionContext, Future }

trait TrackingActions {
  self: UserActions with Controller =>

  implicit val ec: ExecutionContext

  def SlackClickTracking(slackTeamIdOpt: Option[SlackTeamId], subactionFallback: String)(implicit slackAnalytics: SlackAnalytics) = MaybeUserAction andThen new ActionFunction[MaybeUserRequest, MaybeUserRequest] {
    override def invokeBlock[A](request: MaybeUserRequest[A], block: (MaybeUserRequest[A]) => Future[Result]): Future[Result] = {
      Future {
        request.getQueryString("t").foreach { signedTrackingParams =>
          val params = KifiUrlRedirectHelper.extractTrackingParams(signedTrackingParams, Some("ascii"))
          for {
            slackTeamId <- slackTeamIdOpt
            slackChannelId <- params.getParam("slackChannelId").flatMap(_.value.map(SlackChannelId(_)))
            category <- params.getParam("category").flatMap(_.value.map(NotificationCategory(_)))
            subaction = params.getParam("subaction").flatMap(_.value).getOrElse(subactionFallback)
          } {
            slackAnalytics.trackNotificationClicked(slackTeamId, slackChannelId, category, subaction)
          }
        }
      }
      block(request)
    }
  }
}
