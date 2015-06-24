package com.keepit.shoebox.controllers

import com.keepit.commanders.{ OrganizationCommander, OrganizationMembershipCommander }
import com.keepit.common.controller.{ MaybeUserRequest, UserActions, UserRequest }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.Id
import com.keepit.model.{ OrganizationFail, OrganizationPermission, Organization, User }
import play.api.libs.json.Json
import play.api.mvc._

import scala.concurrent.Future

trait OrganizationAccessActions {
  self: UserActions with Controller =>

  val publicIdConfig: PublicIdConfiguration
  implicit private val implicitPublicId = publicIdConfig
  val orgCommander: OrganizationCommander
  val orgMembershipCommander: OrganizationMembershipCommander

  case class OrganizationRequest[T](request: MaybeUserRequest[T], orgId: Id[Organization], authToken: Option[String], permissions: Set[OrganizationPermission]) extends WrappedRequest[T](request) {
    def userIdOpt = request.userIdOpt
  }

  def OrganizationAction(id: PublicId[Organization], permissions: OrganizationPermission*) = MaybeUserAction andThen new ActionFunction[MaybeUserRequest, OrganizationRequest] {
    override def invokeBlock[A](request: MaybeUserRequest[A], block: (OrganizationRequest[A]) => Future[Result]): Future[Result] = {
      parseRequest(id, request) match {
        case Some((orgId, userIdOpt, authToken)) =>
          // Right now all organizations are publicly visible
          val memberPermissions = orgMembershipCommander.getPermissions(orgId, userIdOpt)
          if (permissions forall (memberPermissions.contains)) {
            block(OrganizationRequest(request, orgId, authToken, memberPermissions))
          } else {
            Future.successful(OrganizationFail.INSUFFICIENT_PERMISSIONS.asErrorResponse)
          }
        case _ =>
          Future.successful(OrganizationFail.INVALID_PUBLIC_ID.asErrorResponse)
      }
    }
  }

  def OrganizationAccessCheck(id: PublicId[Organization], permissions: OrganizationPermission*) = new ActionFilter[MaybeUserRequest] {
    def filter[A](input: MaybeUserRequest[A]): Future[Option[Result]] = Future.successful(lookupViewable(id, input, permissions))
  }

  private def lookupViewable[A](orgPubId: PublicId[Organization], input: MaybeUserRequest[A], permissions: Seq[OrganizationPermission]) = {
    parseRequest(orgPubId, input) match {
      case Some((orgId, userIdOpt, authToken)) =>
        // Right now all organizations are publicly visible
        val memberPermissions = orgMembershipCommander.getPermissions(orgId, userIdOpt)
        if (permissions forall (memberPermissions.contains)) {
          None
        } else {
          Some(OrganizationFail.INSUFFICIENT_PERMISSIONS.asErrorResponse)
        }
      case _ =>
        Some(OrganizationFail.INVALID_PUBLIC_ID.asErrorResponse)
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
