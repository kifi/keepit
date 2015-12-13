package com.keepit.controllers.website

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.{ LibraryAccessCommander, LibraryMembershipCommander, PermissionCommander }
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.Database
import com.keepit.common.json.EitherFormat
import com.keepit.discussion.Message
import com.keepit.model.ExternalLibrarySpace.{ ExternalOrganizationSpace, ExternalUserSpace }
import com.keepit.model.LibrarySpace.{ OrganizationSpace, UserSpace }
import com.keepit.model._
import com.keepit.slack.models._
import com.keepit.slack.{ LibraryToSlackChannelPusher, SlackClient, SlackCommander }
import play.api.libs.json.{ JsObject, JsSuccess, Json, JsError }

import scala.concurrent.{ Future, ExecutionContext }
import scala.util.{ Success, Failure }

@Singleton
class DiscussionController @Inject() (
  slackClient: SlackClient,
  slackCommander: SlackCommander,
  libraryAccessCommander: LibraryAccessCommander,
  deepLinkRouter: DeepLinkRouter,
  slackToLibRepo: SlackChannelToLibraryRepo,
  userRepo: UserRepo,
  val userActionsHelper: UserActionsHelper,
  val db: Database,
  implicit val publicIdConfig: PublicIdConfiguration,
  implicit val ec: ExecutionContext)
    extends UserActions with ShoeboxServiceController {

  def markKeepsAsRead() = UserAction(parse.tolerantJson) { request =>
    ???
  }
  def sendMessageOnKeep(keepId: PublicId[Keep]) = UserAction(parse.tolerantJson) { request =>
    ???
  }
  def getMessagesOnKeep(keepId: PublicId[Keep], limit: Int, fromId: Option[String]) = UserAction(parse.tolerantJson) { request =>
    ???
  }
  def editMessageOnKeep(keepId: PublicId[Keep]) = UserAction(parse.tolerantJson) { request =>
    ???
  }
  def deleteMessageOnKeep(keepId: PublicId[Keep]) = UserAction(parse.tolerantJson) { request =>
    ???
  }
}
