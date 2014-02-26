package com.keepit.abook.typeahead

import com.google.inject.Inject
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.typeahead.abook.{EContactTypeaheadKey, EContactTypeahead, EContactTypeaheadCache, EContactTypeaheadStore}
import com.keepit.model.{User, EContact}
import com.keepit.common.logging.Logging
import com.keepit.typeahead.{PrefixFilter, Typeahead}
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.abook.{ABookInfoRepo, EmailParser, EContactRepo}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import scala.concurrent.{Promise, Future, Await}
import scala.concurrent.duration.Duration
import scala.collection.mutable.ArrayBuffer

class EContactABookTypeahead @Inject() (
  db:Database,
  airbrake:AirbrakeNotifier,
  store:EContactTypeaheadStore,
  abookInfoRepo: ABookInfoRepo,
  econtactRepo: EContactRepo,
  cache: EContactTypeaheadCache
) extends Typeahead[EContact, EContact] with Logging {

  override protected def extractName(info: EContact): String = {
    val name = info.name.getOrElse("").trim
    val pres = EmailParser.parse(EmailParser.email, info.email)
    if (pres.successful) {
      s"$name ${pres.get.local.toStrictString}"
    } else {
      airbrake.notify(s"[EContactTypeahead.extractName($info)] Failed to parse email ${info.email}")
      val local = info.email.takeWhile(c => c != '@').trim // best effort
      s"$name $local"
    }
  }

  override protected def extractId(info: EContact): Id[EContact] = info.id.get

  override def getPrefixFilter(userId: Id[User]): Option[PrefixFilter[EContact]] = { // todo(ray):factor out common code
    val (filter, msg) = cache.get(EContactTypeaheadKey(userId)) match {
      case Some(filter) =>
        (Some(new PrefixFilter[EContact](filter)), "Cache.get")
      case None =>
        val (filter, msg) = store.get(userId) match {
          case Some(filter) =>
            (new PrefixFilter[EContact](filter), "Store.get")
          case None =>
            val pFilter = Await.result(build(userId), Duration.Inf)
            store += (userId -> pFilter.data)
            (pFilter, "Built")
        }
        cache.set(EContactTypeaheadKey(userId), filter.data)
        (Some(filter), msg)
    }
    log.info(s"[email.getPrefixFilter($userId)] ($msg) ${filter}")
    filter

  }

  def refresh(userId:Id[User]):Future[Unit] = {
    build(userId) map { filter =>
      store += (userId -> filter.data)
      cache.set(EContactTypeaheadKey(userId), filter.data)
      log.info(s"[refresh($userId)] cache updated; filter=$filter")
    }
  }

  def refreshByIds(userIds:Seq[Id[User]]):Future[Unit] = {
    val futures = new ArrayBuffer[Future[Unit]]
    for (userId <- userIds) {
      futures += refresh(userId)
    }
    Future.sequence(futures.toSeq) map { s => Unit }
  }

  def refreshAll():Future[Unit] = {
    val infos = db.readOnly { implicit ro =>
      abookInfoRepo.all() // only retrieve users with existing abooks (todo: deal with deletes)
    }
    val userIds = infos.foldLeft(Set.empty[Id[User]]) {(a,c) => a + c.userId } // inefficient
    refreshByIds(userIds.toSeq)
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
