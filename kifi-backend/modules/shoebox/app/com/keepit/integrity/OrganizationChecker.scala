package com.keepit.integrity

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.LibraryCommander
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.performance.{ AlertingTimer, StatsdTiming }
import com.keepit.common.time.{ Clock, _ }
import com.keepit.model._
import com.keepit.payments.{ PlanManagementCommander, PaidAccountRepo, PaidAccountStates }

import scala.concurrent.{ Future, ExecutionContext }
import scala.concurrent.ExecutionContext

@Singleton
class OrganizationChecker @Inject() (
    airbrake: AirbrakeNotifier,
    clock: Clock,
    db: Database,
    userRepo: UserRepo,
    libraryRepo: LibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    libraryCommander: LibraryCommander,
    orgRepo: OrganizationRepo,
    orgMembershipRepo: OrganizationMembershipRepo,
    orgInviteRepo: OrganizationInviteRepo,
    orgMembershipCandidateRepo: OrganizationMembershipCandidateRepo,
    paidAccountRepo: PaidAccountRepo,
    planManagementCommander: PlanManagementCommander,
    systemValueRepo: SystemValueRepo,
    implicit val executionContext: ExecutionContext) extends Logging {

  private[this] val lock = new AnyRef
  private val timeSlicer = new TimeSlicer(clock)

  private[integrity] val ORGANIZATION_INTEGRITY_SEQ = Name[SequenceNumber[Organization]]("organization_integrity_plugin")
  private val ORG_FETCH_SIZE = 20

  @AlertingTimer(10 seconds)
  @StatsdTiming("OrganizationChecker.check")
  def check(): Unit = lock.synchronized {
    db.readWrite { implicit s =>
      val lastSeq = systemValueRepo.getSequenceNumber(ORGANIZATION_INTEGRITY_SEQ).getOrElse(SequenceNumber.ZERO[Organization])
      val orgs = orgRepo.getBySequenceNumber(lastSeq, ORG_FETCH_SIZE)
      if (orgs.nonEmpty) {
        orgs.foreach { org =>
          ensureStateIntegrity(org.id.get)
          ensureOrgSystemLibraryIntegrity(org.id.get)
        }
        systemValueRepo.setSequenceNumber(ORGANIZATION_INTEGRITY_SEQ, orgs.map(_.seq).max)
      }
    }
  }

  private def ensureStateIntegrity(orgId: Id[Organization])(implicit session: RWSession) = {
    val org = orgRepo.get(orgId)
    if (org.isInactive) {
      val zombieMemberships = orgMembershipRepo.getAllByOrgId(org.id.get, excludeState = Some(OrganizationMembershipStates.INACTIVE))
      if (zombieMemberships.nonEmpty) {
        airbrake.notify(s"[ORG-STATE-MATCH] Dead org $orgId has zombie memberships for these users: ${zombieMemberships.map(_.userId)}")
        zombieMemberships.foreach(orgMembershipRepo.deactivate)
      }

      val zombieCandidates = orgMembershipCandidateRepo.getAllByOrgId(org.id.get, states = Set(OrganizationMembershipCandidateStates.ACTIVE))
      if (zombieCandidates.nonEmpty) {
        airbrake.notify(s"[ORG-STATE-MATCH] Dead org $orgId has zombie candidates for these users: ${zombieCandidates.map(_.userId)}")
        zombieCandidates.foreach(orgMembershipCandidateRepo.deactivate)
      }

      val zombieInvites = orgInviteRepo.getAllByOrgId(org.id.get, state = OrganizationInviteStates.ACTIVE)
      if (zombieInvites.nonEmpty) {
        airbrake.notify(s"[ORG-STATE-MATCH] Dead org $orgId has zombie invites: ${zombieInvites.map(_.id.get)}")
        zombieInvites.foreach(orgInviteRepo.deactivate)
      }

      val zombiePaidAccount = paidAccountRepo.maybeGetByOrgId(org.id.get, excludeStates = Set(PaidAccountStates.INACTIVE))
      if (zombiePaidAccount.isDefined) {
        airbrake.notify(s"[ORG-STATE-MATCH] Dead org $orgId has zombie paid account: ${zombiePaidAccount.map(_.id.get)}")
        planManagementCommander.deactivatePaidAccountForOrganziation(org.id.get, session)
      }

      val zombieLibs = libraryRepo.getBySpace(org.id.get, excludeState = Some(LibraryStates.INACTIVE))
      if (zombieLibs.nonEmpty) {
        airbrake.notify(s"[ORG-STATE-MATCH] Dead org $orgId has zombie libraries: ${zombieLibs.map(_.id.get)}")
        zombieLibs.collect {
          case lib if lib.isUserCreated => libraryCommander.unsafeModifyLibrary(lib, LibraryModifications(space = Some(lib.ownerId)))
          case lib if lib.isSystemCreated => libraryCommander.unsafeAsyncDeleteLibrary(lib.id.get)
        }
      }
    }
  }
  private def ensureOrgSystemLibraryIntegrity(orgId: Id[Organization])(implicit session: RWSession) = {
    val org = orgRepo.get(orgId)
    if (org.isActive) {
      val orgGeneralLibrary = libraryRepo.getBySpaceAndKind(org.id.get, LibraryKind.SYSTEM_ORG_GENERAL)
      if (orgGeneralLibrary.isEmpty) {
        log.error(s"[ORG-SYSTEM-LIBS] Org $orgId does not have a general library! Adding one.")
        val orgGeneralLib = libraryCommander.unsafeCreateLibrary(LibraryInitialValues.forOrgGeneralLibrary(org), org.ownerId)
        val orgMemberIds = orgMembershipRepo.getAllByOrgId(org.id.get).map(_.userId) - org.ownerId
        orgMemberIds.foreach { userId => // TODO(ryan): you should feel bad about writing this. put the correct `unsafeJoinLibrary` method in LibraryMembershipCommander and call that
          libraryMembershipRepo.save(LibraryMembership(libraryId = orgGeneralLib.id.get, userId = userId, access = LibraryAccess.READ_WRITE))
        }
      } else if (orgGeneralLibrary.size > 1) {
        airbrake.notify(s"[ORG-SYSTEM-LIBS] Org $orgId has ${orgGeneralLibrary.size} general libraries! Ahhhhhh!")
      }
    }
  }
}
