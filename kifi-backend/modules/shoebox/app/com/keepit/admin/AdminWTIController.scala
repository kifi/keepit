package com.keepit.controllers.admin


import com.google.inject.Inject
import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.keepit.commanders.{
  SocialConnectionModificationActor,
  UserConnectionModificationActor,
  InvitationModificationActor,
  SocialUserInfoModificationActor,
  EmailAddressModificationActor
}
import com.keepit.common.actor.ActorInstance
import com.keepit.common.actor.FlushPlease

class AdminWTIController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    socialConnectionModificationActor: ActorInstance[SocialConnectionModificationActor],
    userConnectionModificationActor: ActorInstance[UserConnectionModificationActor],
    socialUserInfoModificationActor: ActorInstance[SocialUserInfoModificationActor],
    invitationModificationActor: ActorInstance[InvitationModificationActor],
    emailAddressModificationActor: ActorInstance[EmailAddressModificationActor]
  ) extends AdminController(actionAuthenticator) {


  def triggerPush() = AdminHtmlAction.authenticated { implicit request =>
    socialConnectionModificationActor.ref ! FlushPlease
    userConnectionModificationActor.ref ! FlushPlease
    socialUserInfoModificationActor.ref ! FlushPlease
    invitationModificationActor.ref ! FlushPlease
    emailAddressModificationActor.ref ! FlushPlease
    Ok("triggered")
  }

}
