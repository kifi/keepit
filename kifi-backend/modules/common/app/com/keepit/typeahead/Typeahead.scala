package com.keepit.typeahead

import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.Id
import com.keepit.common.service.RequestConsolidator
import com.keepit.common.performance._
import com.keepit.model.User
import scala.concurrent._
import scala.concurrent.duration._
import com.keepit.common.logging.{LogPrefix, Logging}
import com.keepit.common.concurrent.{FutureHelpers, ExecutionContext}
import com.keepit.common.healthcheck.AirbrakeNotifier
import scala.collection.mutable.ArrayBuffer
import Logging.LoggerWithPrefix

trait Typeahead[E, I] extends Logging {

  protected def airbrake: AirbrakeNotifier

  protected val consolidateBuildReq = new RequestConsolidator[Id[User], PrefixFilter[E]](10 minutes)

  protected val consolidateFetchReq = new RequestConsolidator[Id[User], Option[PrefixFilter[E]]](15 seconds)

  def getPrefixFilter(userId: Id[User]): Option[PrefixFilter[E]] = {
    try {
      val future = consolidateFetchReq(userId) { id =>
        asyncGetOrCreatePrefixFilter(id).map(Some(_))(ExecutionContext.fj)
      }
      Await.result(future, Duration.Inf)
    } catch {
      case t:Throwable =>
        airbrake.notify(s"[getPrefixFilter($userId)] Caught Exception $t; cause=${t.getCause}",t)
        None
    }
  }

  protected def asyncGetOrCreatePrefixFilter(userId: Id[User]):Future[PrefixFilter[E]]

  protected def getInfos(ids: Seq[Id[E]]): Seq[I]

  protected def getAllInfosForUser(id: Id[User]): Seq[I]

  protected def asyncGetInfos(ids: Seq[Id[E]]): Future[Seq[I]] = SafeFuture { getInfos(ids) }(ExecutionContext.fj)

  protected def asyncGetAllInfosForUser(id: Id[User]): Future[Seq[I]] = SafeFuture { getAllInfosForUser(id) }(ExecutionContext.fj)

  protected def extractId(info: I): Id[E]

  protected def extractName(info: I): String

  def search(userId: Id[User], query: String)(implicit ord: Ordering[TypeaheadHit[I]]): Option[Seq[I]] = timing(s"search($userId,$query)") {
    if (query.trim.length > 0) {
      getPrefixFilter(userId) match {
        case None =>
          log.warn(s"[search($userId,$query)] NO FILTER found")
          None
        case Some(filter) =>
          if (filter.isEmpty) {
            log.info(s"[search($userId,$query)] filter is EMPTY")
            None
          } else {
            val queryTerms = PrefixFilter.normalize(query).split("\\s+")
            search(getInfos(filter.filterBy(queryTerms)), queryTerms)
          }
      }
    } else {
      None
    }
  }

  def asyncTopN(userId: Id[User], query: String, limit:Option[Int])(implicit ord: Ordering[TypeaheadHit[I]]): Future[Option[Seq[TypeaheadHit[I]]]] = {
    if (query.trim.length > 0) {
      getPrefixFilter(userId) match {
        case None =>
          log.warn(s"[asyncTopN($userId,$query)] NO FILTER found")
          Future.successful(None)
        case Some(filter) =>
          if (filter.isEmpty) {
            log.info(s"[asyncTopN($userId,$query)] filter is EMPTY")
            Future.successful(None)
          } else {
            val queryTerms = PrefixFilter.normalize(query).split("\\s+")
            asyncGetInfos(filter.filterBy(queryTerms)).map { infos =>
              topN(infos, queryTerms, limit)
            }(ExecutionContext.fj)
          }
      }
    } else {
      Future.successful(None)
    }
  }

  def topN(infos:Seq[I], queryTerms:Array[String], limit:Option[Int])(implicit ord:Ordering[TypeaheadHit[I]]):Option[Seq[TypeaheadHit[I]]] = {
    if (queryTerms.length > 0) {
      var ordinal = 0
      val hits = infos.map{ info =>
        ordinal += 1
        val name = PrefixFilter.normalize(extractName(info))
        TypeaheadHit(PrefixMatching.distanceWithNormalizedName(name, queryTerms), name, ordinal, info)
      }.collect{
        case elem @ TypeaheadHit(score, name, ordinal, info) if score < 1000000.0d => elem
      }.sorted
      val top = limit map (n => hits.take(n)) getOrElse hits
      top.foreach { s => log.info(s"[topN(${queryTerms.mkString(",")},$limit,#infos=${infos.length})] top=${top.mkString(",")}")}
      Some(top)
    } else {
      None
    }
  }

  def asyncSearch(userId: Id[User], query: String)(implicit ord: Ordering[TypeaheadHit[I]]): Future[Option[Seq[I]]] = {
    asyncTopN(userId, query, None).map { o =>
      o map { s => s.map(_.info) }
    }(ExecutionContext.fj)
  }

  def search(infos: Seq[I], queryTerms: Array[String])(implicit ord: Ordering[TypeaheadHit[I]]): Option[Seq[I]] = timing(s"search(${queryTerms.mkString(",")},#infos=${infos.length})") {
    topN(infos, queryTerms, None) map { s => s.map(_.info) }
  }

  def build(id: Id[User]): Future[PrefixFilter[E]] = {
    consolidateBuildReq(id){ id =>
      SafeFuture {
        timing(s"buildFilter($id)") {
          val builder = new PrefixFilterBuilder[E]
          val allInfos = getAllInfosForUser(id)
          allInfos.foreach(info => builder.add(extractId(info), extractName(info)))
          val filter = builder.build
          log.info(s"[buildFilter($id)] allInfos(len=${allInfos.length})(${allInfos.take(10).mkString(",")}) filter.len=${filter.data.length}")
          filter
        }
      }(ExecutionContext.fj)
    }
  }

  def refresh(id: Id[User]): Future[PrefixFilter[E]] // slow

  def refreshByIds(userIds:Seq[Id[User]]):Future[Unit] = { // consider using zk and/or sqs to track progress
    implicit val prefix = LogPrefix(s"refreshByIds(#ids=${userIds.length})")
    implicit val fj = ExecutionContext.fj

    log.infoP(s"begin re-indexing users ...")
    val grouped = userIds.grouped(50).toSeq
    val groupedF = grouped.zipWithIndex.map { case (batch, i) =>
      log.infoP(s"begin re-indexing for batch $i/${grouped.length} ...")
      val futures = batch.map { userId =>
        () => refresh(userId).map { filter =>
          log.infoP(s"done with re-indexing ${userId}; filter=${filter}")
        }
      }
      () => FutureHelpers.sequentialExec(futures).map{ res =>
        log.infoP(s"done with re-indexing for batch $i/${grouped.length}")
      }
    }
    FutureHelpers.sequentialExec(groupedF).map{ u =>
      log.infoP(s"done with re-indexing all ${userIds.length} users.")
    }
  }

  def refreshAll(): Future[Unit]

}

object TypeaheadHit {
  def defaultOrdering[I] = new Ordering[TypeaheadHit[I]] {
    def compare(x: TypeaheadHit[I], y: TypeaheadHit[I]): Int = {
      var cmp = (x.score compare y.score)
      if (cmp == 0) {
        cmp = x.name compare y.name
        if (cmp == 0) {
          cmp = x.ordinal compare y.ordinal
        }
      }
      cmp
    }
  }
}

case class TypeaheadHit[I](score: Int, name: String, ordinal: Int, info: I)
