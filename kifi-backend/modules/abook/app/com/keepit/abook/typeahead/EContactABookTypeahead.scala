package com.keepit.abook.typeahead

import com.google.inject.Inject
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.typeahead.abook._
import com.keepit.model.{User, EContact}
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.abook.{ABookInfoRepo, EContactRepo}
import scala.concurrent.Future
import com.keepit.common.concurrent.ExecutionContext

// ABook-local; direct db access
class EContactABookTypeahead @Inject() (
  db:Database,
  override val airbrake:AirbrakeNotifier,
  cache: EContactTypeaheadCache,
  store: EContactTypeaheadStore,
  abookInfoRepo: ABookInfoRepo,
  econtactRepo: EContactRepo
) extends EContactTypeaheadBase(airbrake, cache, store) {

  def refreshAll():Future[Unit] = {
    log.info("[refreshAll] begin re-indexing ...")
    val abookInfos = db.readOnlyMaster { implicit ro =>
      abookInfoRepo.all() // only retrieve users with existing abooks (todo: deal with deletes)
    }
    log.info(s"[refreshAll] ${abookInfos.length} to be re-indexed; abooks=${abookInfos.take(20).mkString(",")} ...")
    val userIds = abookInfos.foldLeft(Set.empty[Id[User]]) {(a,c) => a + c.userId } // inefficient
    refreshByIds(userIds.toSeq).map { _ =>
      log.info(s"[refreshAll] re-indexing finished.")
    }(ExecutionContext.fj)
  }

  override protected def getAllInfosForUser(id: Id[User]): Seq[EContact] = {
    db.readOnly(attempts = 2) { implicit ro =>
      econtactRepo.getByUserId(id)
    }
  }

  override protected def getInfos(ids: Seq[Id[EContact]]): Seq[EContact] = {
    if (ids.isEmpty) Seq.empty[EContact]
    else {
      db.readOnly(attempts = 2) { implicit ro =>
        econtactRepo.bulkGetByIds(ids).valuesIterator.toSeq
      }
    }
  }

}
