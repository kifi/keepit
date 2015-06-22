package com.keepit.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.Id
import com.keepit.common.time._
import com.keepit.heimdal._
import com.keepit.model._

import scala.concurrent.ExecutionContext

@Singleton
class OrganizationAnalytics @Inject() (heimdal: HeimdalServiceClient,
    implicit val executionContext: ExecutionContext) {

  def trackSentOrganizationInvites(inviterId: Id[User], organization: Organization, invitations: Seq[OrganizationInvite])(implicit eventContext: HeimdalContext) = {
    val when = currentDateTime
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
      heimdal.trackEvent(UserEvent(inviterId, builder.build, UserEventTypes.INVITED, when))
    }
  }

  // Taken from LibraryAnalytics
  private def daysSinceOrganizationCreated(organization: Organization): Float = {
    val daysSinceOrganizationCreated = (currentDateTime.getMillis.toFloat - organization.createdAt.getMillis) / (24 * 3600 * 1000)
    (daysSinceOrganizationCreated - daysSinceOrganizationCreated % 0.0001).toFloat
  }
}
