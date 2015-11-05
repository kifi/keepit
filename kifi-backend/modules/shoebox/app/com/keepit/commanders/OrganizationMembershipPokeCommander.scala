package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.eliza.ElizaServiceClient
import com.keepit.model._
import org.joda.time.Period

import scala.concurrent.ExecutionContext

@ImplementedBy(classOf[OrganizationMembershipPokeCommanderImpl])
trait OrganizationMembershipPokeCommander {
  def poke(userId: Id[User], orgId: Id[Organization]): Either[OrganizationFail, OrganizationMembershipPoke]
}

@Singleton
class OrganizationMembershipPokeCommanderImpl @Inject() (
    implicit val defaultContext: ExecutionContext,
    implicit val publicIdConfig: PublicIdConfiguration,
    airbrake: AirbrakeNotifier,
    elizaClient: ElizaServiceClient,
    db: Database,
    permissionCommander: PermissionCommander,
    userRepo: UserRepo,
    orgRepo: OrganizationRepo,
    orgMembershipRepo: OrganizationMembershipRepo,
    orgMembershipCommander: OrganizationMembershipCommander,
    orgMembershipPokeRepo: OrganizationMembershipPokeRepo,
    orgInviteRepo: OrganizationInviteRepo,
    orgAnalytics: OrganizationAnalytics) extends OrganizationMembershipPokeCommander with Logging {

  private val pokeRefractoryPeriod = Period.weeks(1) // minimum time between pokes

  def poke(userId: Id[User], orgId: Id[Organization]): Either[OrganizationFail, OrganizationMembershipPoke] = {
    db.readWrite { implicit s =>
      if (!permissionCommander.getOrganizationPermissions(orgId, Some(userId)).contains(OrganizationPermission.VIEW_ORGANIZATION)) {
        airbrake.notify("User " + userId + " managed to poke a secret organization" + new Exception())
        Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)
      } else {
        val (existingPokeOpt, existingMembershipOpt, existingInvites) = {
          val existingPoke = orgMembershipPokeRepo.getByOrgIdAndUserId(orgId, userId, excludeStates = Set())
          val existingMembership = orgMembershipRepo.getByOrgIdAndUserId(orgId, userId)
          val existingInvite = orgInviteRepo.getByOrgAndUserId(orgId, userId)
          (existingPoke, existingMembership, existingInvite)
        }

        existingPokeOpt match {
          case Some(existingPoke) if existingPoke.updatedAt.plus(pokeRefractoryPeriod).isAfter(currentDateTime) =>
            Left(OrganizationFail.POKE_ON_COOLDOWN)
          case _ if existingMembershipOpt.isDefined =>
            Left(OrganizationFail.ALREADY_A_MEMBER)
          case _ if existingInvites.nonEmpty =>
            Left(OrganizationFail.ALREADY_INVITED)
          case Some(oldPoke) =>
            Right(orgMembershipPokeRepo.save(oldPoke))
          case None =>
            Right(orgMembershipPokeRepo.save(OrganizationMembershipPoke(organizationId = orgId, userId = userId)))
        }
      }
    }
  }
}
