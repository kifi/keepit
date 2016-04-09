package com.keepit.shoebox.data.assemblers

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.commanders._
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
import com.keepit.eliza.ElizaServiceClient
import com.keepit.model.KeepEventData.{ EditTitle, ModifyRecipients }
import com.keepit.model._
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
    // TODO(cam): implement batched activity-log retrieval
    FutureHelpers.accumulateOneAtATime(keepIds) { keepId =>
      getActivityForKeep(keepId, fromTime, limit = numEventsPerKeep)
    }
  }
  def getActivityForKeep(keepId: Id[Keep], fromTime: Option[DateTime], limit: Int): Future[KeepActivity] = {
    val shoeboxFut = db.readOnlyMasterAsync { implicit s =>
      val keep = keepRepo.get(keepId)
      val sourceAttr = keepSourceCommander.getSourceAttributionForKeep(keepId)
      val ktus = ktuRepo.getAllByKeepId(keepId)
      val ktls = ktlRepo.getAllByKeepId(keepId)
      val events = eventRepo.pageForKeep(keepId, fromTime, limit)
      (keep, sourceAttr, events, ktus, ktls)
    }
    val elizaFut = eliza.getDiscussionsForKeeps(Set(keepId), limit).map(_.get(keepId))

    val basicModelFut = shoeboxFut.map {
      case (keep, sourceAttr, events, ktus, ktls) =>
        db.readOnlyMaster { implicit s =>
          val (usersFromEvents, libsFromEvents) = KeepEvent.idsInvolved(events)

          val libsNeeded: Seq[Id[Library]] = ktls.map(_.libraryId) ++ libsFromEvents
          val libById = libRepo.getActiveByIds(libsNeeded.toSet)

          val basicOrgById = basicOrgGen.getBasicOrganizations(libById.values.flatMap(_.organizationId).toSet)
          val basicOrgByLibId = libById.flatMapValues { library =>
            library.organizationId.flatMap(basicOrgById.get)
          }

          val basicUserById = {
            val ktuUsers = ktus.map(_.userId)
            val libOwners = libById.map { case (libId, library) => library.ownerId }
            basicUserRepo.loadAllActive((ktuUsers ++ libOwners ++ usersFromEvents).toSet)
          }
          val basicLibById = basicLibGen.getBasicLibraries(libById.keySet)
          (basicUserById, basicLibById, basicOrgByLibId)
        }
    }

    for {
      (keep, sourceAttrOpt, events, ktus, ktls) <- shoeboxFut
      (elizaActivityOpt) <- elizaFut
      (userById, libById, orgByLibId) <- basicModelFut
    } yield {
      KeepActivityGen.generateKeepActivity(keep, sourceAttrOpt, events, elizaActivityOpt, ktls, ktus, userById, libById, orgByLibId, limit)
    }
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
