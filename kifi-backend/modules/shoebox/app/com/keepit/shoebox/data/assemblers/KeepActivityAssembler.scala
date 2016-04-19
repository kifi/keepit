package com.keepit.shoebox.data.assemblers

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.commanders._
import com.keepit.common.core.iterableExtensionOps
import com.keepit.commanders.gen.KeepActivityGen.SerializationInfo
import com.keepit.commanders.gen.{ BasicLibraryGen, BasicOrganizationGen, KeepActivityGen }
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.core.mapExtensionOps
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.performance.StatsdTimingAsync
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.S3ImageConfig
import com.keepit.discussion.Discussion
import com.keepit.eliza.ElizaServiceClient
import com.keepit.model.KeepEventData.{ EditTitle, ModifyRecipients }
import com.keepit.model._
import com.keepit.social.BasicUser
import org.joda.time.DateTime

import scala.concurrent.{ ExecutionContext, Future }

@ImplementedBy(classOf[KeepActivityAssemblerImpl])
trait KeepActivityAssembler {
  def getActivityForKeeps(keepIds: Set[Id[Keep]], fromTime: Option[DateTime], numEventsPerKeep: Int): Future[Map[Id[Keep], KeepActivity]]
  def getActivityForKeep(keepId: Id[Keep], fromTime: Option[DateTime], limit: Int): Future[KeepActivity]
  def assembleBasicKeepEvent(keepId: Id[Keep], event: KeepEvent)(implicit session: RSession): BasicKeepEvent
}

class KeepActivityAssemblerImpl @Inject() (
  db: Database,
  keepRepo: KeepRepo,
  libRepo: LibraryRepo,
  basicUserRepo: BasicUserRepo,
  basicLibGen: BasicLibraryGen,
  basicOrgGen: BasicOrganizationGen,
  ktlRepo: KeepToLibraryRepo,
  ktuRepo: KeepToUserRepo,
  eventRepo: KeepEventRepo,
  keepSourceCommander: KeepSourceCommander,
  eliza: ElizaServiceClient,
  private implicit val airbrake: AirbrakeNotifier,
  private implicit val publicIdConfig: PublicIdConfiguration,
  private implicit val imageConfig: S3ImageConfig,
  private implicit val executionContext: ExecutionContext)
    extends KeepActivityAssembler {

  @StatsdTimingAsync("KeepActivityAssembler.getActivityForKeeps")
  def getActivityForKeeps(keepIds: Set[Id[Keep]], fromTime: Option[DateTime], numEventsPerKeep: Int): Future[Map[Id[Keep], KeepActivity]] = {
    val shoeboxFut = db.readOnlyMasterAsync { implicit s =>
      val keepsById = keepRepo.getActiveByIds(keepIds)
      val sourceAttrsByKeep = keepSourceCommander.getSourceAttributionForKeeps(keepIds)
      val eventsByKeep = keepIds.augmentWith(keepId => eventRepo.pageForKeep(keepId, fromTime, limit = numEventsPerKeep, excludeKinds = KeepEventKind.hideForNow)).toMap
      val ktusByKeep = ktuRepo.getAllByKeepIds(keepIds)
      val ktlsByKeep = ktlRepo.getAllByKeepIds(keepIds)
      (keepsById, sourceAttrsByKeep, eventsByKeep, ktusByKeep, ktlsByKeep)
    }
    val elizaFut = eliza.getDiscussionsForKeeps(keepIds, fromTime, numEventsPerKeep)

    for {
      (keepsById, sourceAttrsByKeep, eventsByKeep, ktusByKeep, ktlsByKeep) <- shoeboxFut
      discussionsByKeep <- elizaFut
    } yield keepsById.map {
      case (kId, keep) => kId -> keepActivityAssemblyHelper(
        keep = keep,
        sourceAttrOpt = sourceAttrsByKeep.get(kId),
        events = eventsByKeep.getOrElse(kId, throw new Exception()),
        ktus = ktusByKeep.getOrElse(kId, Seq.empty),
        ktls = ktlsByKeep.getOrElse(kId, Seq.empty),
        discussion = discussionsByKeep.get(kId),
        limit = numEventsPerKeep
      )
    }
  }

  def getActivityForKeep(keepId: Id[Keep], fromTime: Option[DateTime], limit: Int): Future[KeepActivity] = {
    getActivityForKeeps(Set(keepId), fromTime, limit).map(_.getOrElse(keepId, throw new Exception(s"Could not generate activity for keep $keepId")))
  }

  private def keepActivityAssemblyHelper(
    keep: Keep,
    sourceAttrOpt: Option[(SourceAttribution, Option[BasicUser])],
    events: Seq[KeepEvent],
    ktus: Seq[KeepToUser],
    ktls: Seq[KeepToLibrary],
    discussion: Option[Discussion],
    limit: Int): KeepActivity = {
    val (userById, libById, orgByLibId) = db.readOnlyMaster { implicit s =>
      val (usersFromEvents, libsFromEvents) = KeepEvent.idsInvolved(events)
      val libById = {
        val libsNeeded = ktls.map(_.libraryId).toSet ++ libsFromEvents
        libRepo.getActiveByIds(libsNeeded)
      }
      val basicOrgByLibId = {
        val basicOrgById = basicOrgGen.getBasicOrganizations(libById.values.flatMap(_.organizationId).toSet)
        libById.flatMapValues(_.organizationId.flatMap(basicOrgById.get))
      }
      val basicUserById = {
        val ktuUsers = ktus.map(_.userId).toSet
        val libOwners = libById.values.map(_.ownerId).toSet
        basicUserRepo.loadAllActive(ktuUsers ++ libOwners ++ usersFromEvents)
      }
      val basicLibById = basicLibGen.getBasicLibraries(libById.keySet)
      (basicUserById, basicLibById, basicOrgByLibId)
    }
    implicit val info = SerializationInfo(userById, libById, orgByLibId)
    KeepActivityGen.generateKeepActivity(keep, sourceAttrOpt, events, discussion, ktls, ktus, limit)
  }

  def assembleBasicKeepEvent(keepId: Id[Keep], event: KeepEvent)(implicit session: RSession): BasicKeepEvent = {
    val (userIds, libraries) = KeepEvent.idsInvolved(Seq(event))
    implicit val info = KeepActivityGen.SerializationInfo(
      userById = basicUserRepo.loadAllActive(userIds),
      libById = basicLibGen.getBasicLibraries(libraries),
      orgByLibraryId = Map.empty
    )
    KeepActivityGen.generateKeepEvent(keepId, event)
  }
}
