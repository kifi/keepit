package com.keepit.shoebox.controllers

import com.keepit.commanders.{ OrganizationCommander, OrganizationMembershipCommander }
import com.keepit.common.controller.{ MaybeUserRequest, UserActions, UserRequest }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.Id
import com.keepit.model.{ Organization, User }
import play.api.libs.json.Json
import play.api.mvc.{ ActionFilter, Controller, Result }

import scala.concurrent.Future

trait OrganizationAccessActions {
  self: UserActions with Controller =>

  val publicIdConfig: PublicIdConfiguration
  implicit private val implicitPublicId = publicIdConfig
  val orgCommander: OrganizationCommander
  val orgMembershipCommander: OrganizationMembershipCommander

  def OrganizationViewAction(id: PublicId[Organization]) = new ActionFilter[MaybeUserRequest] {
    def filter[A](input: MaybeUserRequest[A]): Future[Option[Result]] = Future.successful(lookupViewable(id, input))
  }

  private def lookupViewable[A](orgPubId: PublicId[Organization], input: MaybeUserRequest[A]) = {
    parseRequest(orgPubId, input) match {
      case Some((orgId, userIdOpt, authToken)) =>
        // Right now all organizations are publicly visible
        val access = true // TODO: this ought to be removed when Leo gets to OrganizationAccessAction.byPermission
        if (access) {
          None
        } else {
          Some(Forbidden(Json.obj("error" -> "permission_denied")))
        }
      case _ =>
        Some(BadRequest(Json.obj("error" -> "invalid_id")))
    }
  }

  private def parseRequest[A](orgPubId: PublicId[Organization], input: MaybeUserRequest[A]): Option[(Id[Organization], Option[Id[User]], Option[String])] = {
    val userIdOpt: Option[Id[User]] = input match {
      case userRequest: UserRequest[A] => Some(userRequest.userId)
      case _ => None
    }

    val orgIdOpt = Organization.decodePublicId(orgPubId).toOption
    orgIdOpt.map { orgId =>
      (orgId, userIdOpt, input.getQueryString("authToken"))
    }
  }
}
