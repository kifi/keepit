package com.keepit.shoebox.controllers

import com.keepit.commanders.{ OrganizationInviteCommander, OrganizationCommander, OrganizationMembershipCommander }
import com.keepit.common.controller.{ NonUserRequest, MaybeUserRequest, UserActions, UserRequest }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.Id
import com.keepit.model._
import play.api.libs.json.Json
import play.api.mvc._

import scala.concurrent.Future
import scala.util.{ Failure, Success }

trait OrganizationAccessActions {
  self: UserActions with Controller =>

  val publicIdConfig: PublicIdConfiguration
  implicit private val implicitPublicId = publicIdConfig
  val orgMembershipCommander: OrganizationMembershipCommander
  val orgInviteCommander: OrganizationInviteCommander

  case class OrganizationRequest[T](request: MaybeUserRequest[T], orgId: Id[Organization], authToken: Option[String], permissions: Set[OrganizationPermission]) extends WrappedRequest[T](request)
  case class OrganizationUserRequest[T](request: UserRequest[T], orgId: Id[Organization], authToken: Option[String], permissions: Set[OrganizationPermission]) extends WrappedRequest[T](request)

  def OrganizationUserAction(pubId: PublicId[Organization], requiredPermissions: OrganizationPermission*) = UserAction andThen new ActionFunction[UserRequest, OrganizationUserRequest] {
    override def invokeBlock[A](request: UserRequest[A], block: (OrganizationUserRequest[A]) => Future[Result]): Future[Result] = {
      Organization.decodePublicId(pubId) match {
        case Failure(e) => Future.successful(OrganizationFail.INVALID_PUBLIC_ID.asErrorResponse)
        case Success(orgId) =>
          if (doesUserHaveRequiredPermissions(orgId, Some(request.userId), requiredPermissions.toSet)) {
            val authTokenOpt = request.getQueryString("authToken")
            block(OrganizationUserRequest(request, orgId, authTokenOpt, requiredPermissions.toSet))
          } else {
            Future.successful(OrganizationFail.INSUFFICIENT_PERMISSIONS.asErrorResponse)
          }
      }
    }
  }

  private def doesUserHaveRequiredPermissions(orgId: Id[Organization], userIdOpt: Option[Id[User]], explicitlyRequiredPermissions: Set[OrganizationPermission]): Boolean = {
    val requiredPermissions = explicitlyRequiredPermissions + OrganizationPermission.VIEW_ORGANIZATION
    val memberPermissions = orgMembershipCommander.getPermissions(orgId, userIdOpt)
    requiredPermissions.subsetOf(memberPermissions)
  }

  def OrganizationAction(id: PublicId[Organization], authTokenOpt: Option[String], requiredPermissions: OrganizationPermission*) = MaybeUserAction andThen new ActionFunction[MaybeUserRequest, OrganizationRequest] {
    override def invokeBlock[A](maybeRequest: MaybeUserRequest[A], block: (OrganizationRequest[A]) => Future[Result]): Future[Result] = {
      Organization.decodePublicId(id) match {
        case Success(orgId) =>
          maybeRequest match {
            case request: UserRequest[_] if request.experiments.contains(UserExperimentType.ORGANIZATION) =>
              val userIdOpt: Option[Id[User]] = request match {
                case userRequest: UserRequest[A] => Some(userRequest.userId)
                case _ => None
              }
              val requiredPermissionsSet = requiredPermissions.toSet + OrganizationPermission.VIEW_ORGANIZATION
              val memberPermissions = orgMembershipCommander.getPermissions(orgId, userIdOpt)
              if (requiredPermissionsSet.subsetOf(memberPermissions)) {
                block(OrganizationRequest(request, orgId, authTokenOpt, memberPermissions))
              } else {
                Future.successful(OrganizationFail.INSUFFICIENT_PERMISSIONS.asErrorResponse)
              }
            case request: NonUserRequest[_] =>
              if (authTokenOpt.exists(authToken => orgInviteCommander.isAuthValid(orgId, authToken))) {
                val memberPermissions = orgMembershipCommander.getPermissions(orgId, None)
                block(OrganizationRequest(request, orgId, authTokenOpt, memberPermissions))
              } else {
                Future.successful(OrganizationFail.INVALID_AUTHTOKEN.asErrorResponse)
              }
            case _ => Future.successful(NotFound) // we can remove this after we remove the OrgExperiment guard

          }
        case _ =>
          Future.successful(OrganizationFail.INVALID_PUBLIC_ID.asErrorResponse)
      }
    }
  }
}
