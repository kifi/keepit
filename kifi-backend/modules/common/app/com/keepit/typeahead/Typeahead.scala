package com.keepit.typeahead

import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.Id
import com.keepit.common.service.RequestConsolidator
import com.keepit.common.performance._
import com.keepit.model.User
//import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent._
import scala.concurrent.duration._
import com.keepit.common.logging.Logging

trait Typeahead[E, I] extends Logging {

  protected val consolidateBuildReq = new RequestConsolidator[Id[User], PrefixFilter[E]](10 minutes)

  def getPrefixFilter(userId: Id[User]): Option[PrefixFilter[E]]

  protected def getInfos(ids: Seq[Id[E]]): Seq[I]

  protected def getAllInfosForUser(id: Id[User]): Seq[I]

  protected def asyncGetInfos(ids: Seq[Id[E]]): Future[Seq[I]] = SafeFuture { getInfos(ids) }(com.keepit.common.concurrent.ExecutionContext.fj)

  protected def asyncGetAllInfosForUser(id: Id[User]): Future[Seq[I]] = SafeFuture { getAllInfosForUser(id) }(com.keepit.common.concurrent.ExecutionContext.fj)

  protected def extractId(info: I): Id[E]

  protected def extractName(info: I): String

  def search(userId: Id[User], query: String)(implicit ord: Ordering[TypeaheadHit[I]]): Option[Seq[I]] = timing(s"search($userId,$query)") {
    if (query.trim.length > 0) {
      getPrefixFilter(userId) match {
        case None =>
          log.warn(s"[search($userId,$query)] NO FILTER found. res=NONE")
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

  // todo(ray): consolidator
  def asyncSearch(userId: Id[User], query: String)(implicit ord: Ordering[TypeaheadHit[I]]): Future[Option[Seq[I]]] = {
    if (query.trim.length > 0) {
      getPrefixFilter(userId) match {
        case None =>
          log.warn(s"[asyncSearch($userId,$query)] NO FILTER found")
          Future.successful(None)
        case Some(filter) =>
          if (filter.isEmpty) {
            log.info(s"[asyncSearch($userId,$query)] filter is EMPTY")
            Future.successful(None)
          } else {
            val queryTerms = PrefixFilter.normalize(query).split("\\s+")
            implicit val fjCtx = com.keepit.common.concurrent.ExecutionContext.fj
            asyncGetInfos(filter.filterBy(queryTerms)) map { infos =>
              search(infos, queryTerms)
            }
          }
      }
    } else {
      Future.successful(None)
    }
  }

  def search(infos: Seq[I], queryTerms: Array[String])(implicit ord: Ordering[TypeaheadHit[I]]): Option[Seq[I]] = timing(s"search(${queryTerms.mkString(",")},#infos=${infos.length})") {
    if (queryTerms.length > 0) {
      var ordinal = 0
      val hits = infos.map{ info =>
        ordinal += 1
        val name = PrefixFilter.normalize(extractName(info))
        TypeaheadHit(PrefixMatching.distanceWithNormalizedName(name, queryTerms), name, ordinal, info)
      }.collect{ case elem @ TypeaheadHit(score, name, ordinal, info) if score < 1000000.0d => elem }.sorted.map(_.info)

      Some(hits)
    } else {
      None
    }
  }

  def build(id: Id[User]): Future[PrefixFilter[E]] = {
    consolidateBuildReq(id){ id =>
      SafeFuture {
        timing(s"build($id)") {
          val builder = new PrefixFilterBuilder[E]
          val allInfos = getAllInfosForUser(id)
          allInfos.foreach(info => builder.add(extractId(info), extractName(info)))
          val filter = builder.build
          log.info(s"[build($id)] allInfos(len=${allInfos.length})(${allInfos.take(10).mkString(",")}) filter.len=${filter.data.length}")
          filter
        }
      }(com.keepit.common.concurrent.ExecutionContext.fj)
    }
  }

  def refresh(id: Id[User]): Future[Unit] // slow

  def refreshByIds(ids: Seq[Id[User]]): Future[Unit]

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
