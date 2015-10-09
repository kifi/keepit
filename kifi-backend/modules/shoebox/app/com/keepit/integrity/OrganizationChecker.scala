package com.keepit.integrity

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.LibraryCommander
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.performance.{ AlertingTimer, StatsdTiming }
import com.keepit.common.time.{ Clock, _ }
import com.keepit.model._
import com.keepit.payments.{ PaidAccountRepo, PaidAccountStates, PlanManagementCommander }

import scala.concurrent.duration.{ Duration, SECONDS }
import scala.concurrent.{ Await, ExecutionContext, Future }

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
  private val MAX_CHECK_TIME = Duration(10, SECONDS)

  @AlertingTimer(10 seconds)
  @StatsdTiming("OrganizationChecker.check")
  def check(): Unit = lock.synchronized {
    val (allOrgs, brokenOrgs) = db.readOnlyReplica { implicit s =>
      val lastSeq = systemValueRepo.getSequenceNumber(ORGANIZATION_INTEGRITY_SEQ).getOrElse(SequenceNumber.ZERO[Organization])
      val orgsToCheck = orgRepo.getBySequenceNumber(lastSeq, ORG_FETCH_SIZE).toSet
      val brokenOrgs = orgsToCheck.filter { org => orgHasBrokenState(org) || orgHasBrokenSystemLibraries(org) }
      (orgsToCheck, brokenOrgs)
    }

    if (brokenOrgs.nonEmpty) log.error("[ORG-CHECKER] Found broken orgs: " + brokenOrgs.map(_.id.get))

    if (allOrgs.nonEmpty) {
      val maxSeq = allOrgs.map(_.seq).max
      val tryToFixAllOrgsFut = Future.sequence { brokenOrgs.map(org => fixOrg(org.id.get)) }.map { _ => () }
      Await.result(tryToFixAllOrgsFut, MAX_CHECK_TIME)
      db.readWrite { implicit session =>
        systemValueRepo.setSequenceNumber(ORGANIZATION_INTEGRITY_SEQ, maxSeq)
      }
    }
  }

  def fixOrg(orgId: Id[Organization]): Future[Unit] = {
    log.info(s"[ORG-CHECKER] Fixing org $orgId")
    val fixes = Set(
      ensureStateIntegrity(orgId),
      ensureOrgSystemLibraryIntegrity(orgId)
    )
    Future.sequence(fixes).map { _ => log.info(s"[ORG-CHECKER] Done fixing org $orgId") }
  }

  private def orgHasBrokenState(org: Organization)(implicit session: RSession): Boolean = {
    if (org.isActive) false
    else {
      val zombieMemberships = orgMembershipRepo.getAllByOrgId(org.id.get, excludeState = Some(OrganizationMembershipStates.INACTIVE))
      val zombieCandidates = orgMembershipCandidateRepo.getAllByOrgId(org.id.get, states = Set(OrganizationMembershipCandidateStates.ACTIVE))
      val zombieInvites = orgInviteRepo.getAllByOrgId(org.id.get, state = OrganizationInviteStates.ACTIVE)
      val zombiePaidAccount = paidAccountRepo.maybeGetByOrgId(org.id.get, excludeStates = Set(PaidAccountStates.INACTIVE))
      val zombieLibs = libraryRepo.getBySpace(org.id.get, excludeState = Some(LibraryStates.INACTIVE))

      if (zombieLibs.nonEmpty) log.info(s"[ORG-CHECKER] the reason ${org.id.get} is broken is zombie libraries: ${zombieLibs.map(_.id.get)}")

      zombieMemberships.nonEmpty || zombieCandidates.nonEmpty || zombieInvites.nonEmpty || zombiePaidAccount.isDefined || zombieLibs.nonEmpty
    }
  }
  private def orgHasBrokenSystemLibraries(org: Organization)(implicit session: RSession): Boolean = {
    if (org.isInactive) false
    else {
      val orgGeneralLibrary = libraryRepo.getBySpaceAndKind(org.id.get, LibraryKind.SYSTEM_ORG_GENERAL)
      orgGeneralLibrary.size != 1
    }
  }

  private def ensureStateIntegrity(orgId: Id[Organization]): Future[Unit] = {
    log.info(s"[ORG-CHECKER] checking the state integrity of $orgId")
    val org = db.readOnlyReplica { implicit session => orgRepo.get(orgId) }
    if (org.isActive) {
      log.info(s"[ORG-CHECKER] no need to worry about $orgId's state, it's still active: $org")
      Future.successful(())
    } else {
      log.info(s"[ORG-CHECKER] Ensuring the integrity of dead org $orgId")
      // There is some easy stuff that can be done synchronously
      db.readWrite { implicit session =>
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
          planManagementCommander.deactivatePaidAccountForOrganization(org.id.get, session)
        }
      }

      val zombieLibs = db.readOnlyReplica { implicit session =>
        libraryRepo.getBySpace(org.id.get, excludeState = Some(LibraryStates.INACTIVE))
      }
      val libIntegrityFuts = if (zombieLibs.isEmpty) {
        Set.empty[Future[Unit]]
      } else {
        airbrake.notify(s"[ORG-STATE-MATCH] Dead org $orgId has zombie libraries: ${zombieLibs.map(_.id.get)}")
        zombieLibs.collect {
          case lib if lib.canBeModified => libraryCommander.unsafeModifyLibrary(lib, LibraryModifications(space = Some(lib.ownerId))).keepChanges
          case lib if lib.isSystemLibrary => libraryCommander.unsafeAsyncDeleteLibrary(lib.id.get)
        }
      }
      Future.sequence(libIntegrityFuts).map { _ => () }
    }
  }
  private def ensureOrgSystemLibraryIntegrity(orgId: Id[Organization]): Future[Unit] = db.readWriteAsync { implicit session =>
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
