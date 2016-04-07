package com.keepit.shoebox.data.assemblers

import com.google.inject.{ImplementedBy, Inject}
import com.keepit.commanders._
import com.keepit.commanders.gen.{BasicLibraryGen, BasicOrganizationGen, KeepActivityGen}
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.core.mapExtensionOps
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.performance.StatsdTimingAsync
import com.keepit.common.social.BasicUserRepo
import com.keepit.eliza.ElizaServiceClient
import com.keepit.model._
import org.joda.time.DateTime

import scala.concurrent.{ExecutionContext, Future}

@ImplementedBy(classOf[KeepInfoAssemblerImpl])
trait KeepActivityAssembler {
  def getActivityForKeeps(keepIds: Set[Id[Keep]], fromTime: Option[DateTime], numEventsPerKeep: Int): Future[Map[Id[Keep], KeepActivity]]
  def getActivityForKeep(keepId: Id[Keep], fromTime: Option[DateTime], limit: Int): Future[KeepActivity]
}

class KeepActivityAssemblerImpl @Inject() (
  db: Database,
  keepRepo: KeepRepo,
  libRepo: LibraryRepo,
  basicUserRepo: BasicUserRepo,
  basicLibGen: BasicLibraryGen, // This is not used, but I think it should be
  basicOrgGen: BasicOrganizationGen,
  ktlRepo: KeepToLibraryRepo,
  ktuRepo: KeepToUserRepo,
  keepSourceCommander: KeepSourceCommander,
  eliza: ElizaServiceClient,
  private implicit val airbrake: AirbrakeNotifier,
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
      (keep, sourceAttr, ktus, ktls)
    }
    val elizaFut = eliza.getCrossServiceKeepActivity(Set(keepId), fromTime, limit).map(_.get(keepId))

    val basicModelFut = shoeboxFut.map {
      case (keep, sourceAttr, ktus, ktls) =>
        db.readOnlyMaster { implicit s =>
          val libById = libRepo.getActiveByIds(ktls.map(_.libraryId).toSet)
          val basicOrgById = basicOrgGen.getBasicOrganizations(libById.values.flatMap(_.organizationId).toSet)
          val basicOrgByLibId = libById.flatMapValues { library =>
            library.organizationId.flatMap(basicOrgById.get)
          }
          val basicUserById = {
            val ktuUsers = ktus.map(_.userId)
            val libOwners = libById.map { case (libId, library) => library.ownerId }
            basicUserRepo.loadAllActive((ktuUsers ++ libOwners).toSet)
          }
          val basicLibById = libById.map {
            case (libId, library) =>
              // I think we should use `BasicLibraryGen` here instead of constructing them manually
              libId -> BasicLibrary(library, basicUserById(library.ownerId), basicOrgByLibId.get(libId).map(_.handle))
          }
          (basicUserById, basicLibById, basicOrgByLibId)
        }
    }

    for {
      (keep, sourceAttrOpt, ktus, ktls) <- shoeboxFut
      (elizaActivityOpt) <- elizaFut
      (userById, libById, orgByLibId) <- basicModelFut
    } yield {
      KeepActivityGen.generateKeepActivity(keep, sourceAttrOpt, elizaActivityOpt, ktls, ktus, userById, libById, orgByLibId, limit)
    }
  }
}
