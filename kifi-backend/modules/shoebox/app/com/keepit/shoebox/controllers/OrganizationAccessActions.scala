package com.keepit.shoebox.controllers

import com.keepit.commanders.{ OrganizationMembershipCommander, OrganizationCommander }
import com.keepit.common.controller.{ MaybeUserRequest, UserActions, UserRequest }
import com.keepit.common.crypto.PublicId
import com.keepit.common.db.Id
import com.keepit.model.{ OrganizationPermission, Organization, User }
import play.api.libs.json.Json
import play.api.mvc.{ ActionFilter, Controller, Result }

import scala.concurrent.Future

trait OrganizationAccessActions {
  self: UserActions with Controller =>

  val publicIdConfig: com.keepit.common.crypto.PublicIdConfiguration
  implicit private val implicitPublicId = publicIdConfig
  val orgCommander: OrganizationCommander
  val orgMembershipCommander: OrganizationMembershipCommander

  def OrganizationViewAction(id: PublicId[Organization]) = new ActionFilter[MaybeUserRequest] {
    def filter[A](input: MaybeUserRequest[A]): Future[Option[Result]] = Future.successful(lookupViewable(id, input))
  }

  def OrganizationEditAction(id: PublicId[Organization]): ActionFilter[UserRequest] = new ActionFilter[UserRequest] {
    def filter[A](input: UserRequest[A]): Future[Option[Result]] = Future.successful(lookupPermission(id, input, OrganizationPermission.EDIT_ORGANIZATION))
  }
  def OrganizationInviteAction(id: PublicId[Organization]): ActionFilter[UserRequest] = new ActionFilter[UserRequest] {
    def filter[A](input: UserRequest[A]): Future[Option[Result]] = Future.successful(lookupPermission(id, input, OrganizationPermission.INVITE_MEMBERS))
  }
  def OrganizationAddLibraryAction(id: PublicId[Organization]): ActionFilter[UserRequest] = new ActionFilter[UserRequest] {
    def filter[A](input: UserRequest[A]): Future[Option[Result]] = Future.successful(lookupPermission(id, input, OrganizationPermission.ADD_LIBRARIES))
  }
  def OrganizationRemoveLibraryAction(id: PublicId[Organization]): ActionFilter[UserRequest] = new ActionFilter[UserRequest] {
    def filter[A](input: UserRequest[A]): Future[Option[Result]] = Future.successful(lookupPermission(id, input, OrganizationPermission.REMOVE_LIBRARIES))
  }

  // Helpers:

  private def lookupViewable[A](orgPubId: PublicId[Organization], input: MaybeUserRequest[A]) = {
    parseRequest(orgPubId, input) match {
      case Some((orgId, userIdOpt, _)) =>
        val access = orgCommander.canViewOrganization(userIdOpt, orgId)
        if (access) {
          None
        } else {
          Some(Forbidden(Json.obj("error" -> "permission_denied")))
        }
      case _ =>
        Some(BadRequest(Json.obj("error" -> "invalid_id")))
    }
  }

  private def lookupPermission[A](orgPubId: PublicId[Organization], input: MaybeUserRequest[A], p: OrganizationPermission) = {
    parseRequest(orgPubId, input) match {
      case Some((orgId, Some(userId), _)) =>
        orgMembershipCommander.getMemberPermissions(orgId, userId) match {
          case Some(permissions) if permissions.contains(p) => None
          case _ => Some(Forbidden(Json.obj("error" -> "permission_denied")))
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
