package com.keepit.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.Id
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time._
import com.keepit.heimdal._
import com.keepit.model._
import com.keepit.social.NonUserKinds

import scala.concurrent.ExecutionContext

@Singleton
class OrganizationAnalytics @Inject() (heimdal: HeimdalServiceClient,
    implicit val executionContext: ExecutionContext) {

  def trackSentOrganizationInvites(inviterId: Id[User], organization: Organization, invitations: Set[OrganizationInvite])(implicit eventContext: HeimdalContext) = {
    val numDays = daysSinceOrganizationCreated(organization)
    SafeFuture {
      val builder = new HeimdalContextBuilder
      builder.addExistingContext(eventContext)
      builder += ("action", "sent")
      builder += ("category", "organizationInvitation")
      builder += ("organizationId", organization.id.get.toString)
      val numUsers = invitations.count(_.userId.isDefined)
      val numEmails = invitations.size - numUsers
      builder += ("numUserInvited", numUsers)
      builder += ("numNonUserInvited", numEmails)
      builder += ("daysSinceOrganizationCreated", numDays)
      heimdal.trackEvent(UserEvent(inviterId, builder.build, UserEventTypes.INVITED))
    }
  }

  // Taken from LibraryAnalytics
  private def daysSinceOrganizationCreated(organization: Organization): Float = {
    val daysSinceOrganizationCreated = (currentDateTime.getMillis.toFloat - organization.createdAt.getMillis) / (24 * 3600 * 1000)
    (daysSinceOrganizationCreated - daysSinceOrganizationCreated % 0.0001).toFloat
  }

  def trackAcceptedEmailInvite(organization: Organization, inviterId: Id[User], inviteeId: Option[Id[User]], emailOpt: Option[EmailAddress])(implicit eventContext: HeimdalContext): Unit = {
    val numDays = daysSinceOrganizationCreated(organization)
    SafeFuture {
      val builder = new HeimdalContextBuilder
      builder.addExistingContext(eventContext)
      builder += ("action", "accepted")
      builder += ("category", "organizationInvitation")
      builder += ("channel", "email")
      builder += ("organizationId", organization.id.get.toString)
      builder += ("inviterId", inviterId.toString)
      builder += ("inviteeId", inviteeId.map(_.toString).getOrElse(""))
      builder += ("inviteeEmail", emailOpt.map(_.address).getOrElse(""))
      builder += ("daysSinceOrganizationCreated", numDays)
      heimdal.trackEvent(UserEvent(inviterId, builder.build, UserEventTypes.INVITED))
    }
  }

  def trackInvitationClicked(organization: Organization, invite: OrganizationInvite)(implicit eventContext: HeimdalContext): Unit = {
    val numDays = daysSinceOrganizationCreated(organization)
    SafeFuture {
      val builder = new HeimdalContextBuilder
      builder.addExistingContext(eventContext)
      builder += ("action", "clickedOrgURL")
      builder += ("organizationId", organization.id.get.toString)
      builder += ("inviterId", invite.inviterId.toString)
      builder += ("inviteeId", invite.userId.map(_.toString).getOrElse(""))
      builder += ("inviteeEmail", invite.emailAddress.map(_.address).getOrElse(""))
      builder += ("daysSinceOrganizationCreated", numDays)
      heimdal.trackEvent(
        invite.userId.map(id => UserEvent(id, builder.build, UserEventTypes.WAS_NOTIFIED))
          .getOrElse(NonUserEvent("", NonUserKinds.email, eventContext, NonUserEventTypes.WAS_NOTIFIED))
      )
    }
  }

  def trackOrganizationEvent(organization: Organization, requester: User, request: OrganizationRequest)(implicit eventContext: HeimdalContext): Unit = {
    val numDays = daysSinceOrganizationCreated(organization)
    SafeFuture {
      val builder = new HeimdalContextBuilder
      builder.addExistingContext(eventContext)
      val action = request match {
        case req: OrganizationCreateRequest => "created"
        case req: OrganizationModifyRequest => "edited"
        case req: OrganizationDeleteRequest => "deleted"
        case req: OrganizationTransferRequest => "transferred"
      }
      builder += ("action", action)
      builder += ("name", organization.name)
      builder += ("organizationId", organization.id.get.toString)
      builder += ("requesterName", requester.fullName)
      builder += ("requesterId", requester.id.get.toString)
      builder += ("daysSinceOrganizationCreated", numDays)
      heimdal.trackEvent(UserEvent(requester.id.get, builder.build, UserEventTypes.CREATED_ORGANIZATION))
    }
  }
    }
  }
}
