package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.{ AdminController, UserActionsHelper, AdminUserActions }
import com.keepit.commanders.{
  SocialConnectionModificationActor,
  UserConnectionModificationActor,
  InvitationModificationActor,
  SocialUserInfoModificationActor
}
import com.keepit.common.actor.ActorInstance
import com.keepit.common.actor.FlushPlease

class AdminWTIController @Inject() (
    val userActionsHelper: UserActionsHelper,
    socialConnectionModificationActor: ActorInstance[SocialConnectionModificationActor],
    userConnectionModificationActor: ActorInstance[UserConnectionModificationActor],
    socialUserInfoModificationActor: ActorInstance[SocialUserInfoModificationActor],
    invitationModificationActor: ActorInstance[InvitationModificationActor]) extends AdminUserActions {

  def triggerPush() = AdminUserPage { implicit request =>
    socialConnectionModificationActor.ref ! FlushPlease
    userConnectionModificationActor.ref ! FlushPlease
    socialUserInfoModificationActor.ref ! FlushPlease
    invitationModificationActor.ref ! FlushPlease
    Ok("triggered")
  }

}
