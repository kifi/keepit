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

  def SlackClickTracking(slackTeamIdOpt: Option[SlackTeamId], subactionFallback: String)(implicit slackAnalytics: SlackAnalytics) = new ActionFunction[MaybeUserRequest, MaybeUserRequest] {
    override def invokeBlock[A](request: MaybeUserRequest[A], block: (MaybeUserRequest[A]) => Future[Result]): Future[Result] = {
      Future {
        val signedParamsOpt = request.getQueryString("t").map(KifiUrlRedirectHelper.extractTrackingParams(_, Some("ascii")))
        def getParam(key: String): Option[String] = {
          val signedOpt = signedParamsOpt.flatMap(_.getParam(key).flatMap(_.value))
          signedOpt.orElse(request.getQueryString(key))
        }

        for {
          slackTeamId <- slackTeamIdOpt
          slackChannelId <- getParam("slackChannelId").map(SlackChannelId(_))
          category <- getParam("category").map(NotificationCategory(_))
          subaction = getParam("subaction").getOrElse(subactionFallback)
        } slackAnalytics.trackNotificationClicked(slackTeamId, slackChannelId, category, subaction)
      }
      block(request)
    }
  }
}
