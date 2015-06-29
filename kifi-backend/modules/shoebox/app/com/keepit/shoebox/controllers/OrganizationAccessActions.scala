package com.keepit.shoebox.controllers

import com.keepit.commanders.{ OrganizationCommander, OrganizationMembershipCommander }
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

  case class OrganizationRequest[T](request: MaybeUserRequest[T], orgId: Id[Organization], authToken: Option[String], permissions: Set[OrganizationPermission]) extends WrappedRequest[T](request)

  def OrganizationAction(id: PublicId[Organization], requiredPermissions: OrganizationPermission*) = MaybeUserAction andThen new ActionFunction[MaybeUserRequest, OrganizationRequest] {
    override def invokeBlock[A](maybeRequest: MaybeUserRequest[A], block: (OrganizationRequest[A]) => Future[Result]): Future[Result] = {
      maybeRequest match {
        case request: UserRequest[_] if request.experiments.contains(ExperimentType.ORGANIZATION) =>
          Organization.decodePublicId(id) match {
            case Success(orgId) =>
              val userIdOpt: Option[Id[User]] = request match {
                case userRequest: UserRequest[A] => Some(userRequest.userId)
                case _ => None
              }
              val requiredPermissionsSet = requiredPermissions.toSet + OrganizationPermission.VIEW_ORGANIZATION
              val memberPermissions = orgMembershipCommander.getPermissions(orgId, userIdOpt)
              if (requiredPermissionsSet.subsetOf(memberPermissions)) {
                val authTokenOpt = request.getQueryString("authToken")
                block(OrganizationRequest(request, orgId, authTokenOpt, memberPermissions))
              } else {
                Future.successful(OrganizationFail.INSUFFICIENT_PERMISSIONS.asErrorResponse)
              }
            case Failure(e) => Future.successful(OrganizationFail.INVALID_PUBLIC_ID.asErrorResponse)
          }
        case _ =>
          Future.successful(OrganizationFail.INSUFFICIENT_PERMISSIONS.asErrorResponse)
      }
    }
  }
}
