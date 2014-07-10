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
import Logging.LoggerWithPrefix
import play.api.libs.json._
import play.api.libs.functional.syntax._

trait Typeahead[E, I] extends Logging {

  protected def airbrake: AirbrakeNotifier

  protected def getOrCreatePrefixFilter(userId: Id[User]): Future[PrefixFilter[E]]

  protected def getInfos(ids: Seq[Id[E]]): Future[Seq[I]]

  protected def getAllInfosForUser(id: Id[User]): Future[Seq[I]]

  protected def extractId(info: I): Id[E]

  protected def extractName(info: I): String

  def topN(userId: Id[User], query: String, limit:Option[Int])(implicit ord: Ordering[TypeaheadHit[I]]): Future[Seq[TypeaheadHit[I]]] = {
    if (query.trim.length <= 0) Future.successful(Seq.empty) else {
      implicit val fj = ExecutionContext.fj
      getPrefixFilter(userId) flatMap { prefixFilterOpt =>
        prefixFilterOpt match {
          case None =>
            log.warn(s"[asyncTopN($userId,$query)] NO FILTER found")
            Future.successful(Seq.empty)
          case Some(filter) =>
            if (filter.isEmpty) {
              log.info(s"[asyncTopN($userId,$query)] filter is EMPTY")
              Future.successful(Seq.empty)
            } else {
              val queryTerms = PrefixFilter.normalize(query).split("\\s+")
              getInfos(filter.filterBy(queryTerms)).map { infos =>
                topNWithInfos(infos, queryTerms, limit)
              }
            }
        }
      }
    }
  }

  private[this] val consolidateFetchReq = new RequestConsolidator[Id[User], Option[PrefixFilter[E]]](15 seconds)

  private[this] def getPrefixFilter(userId: Id[User]): Future[Option[PrefixFilter[E]]] = {
    consolidateFetchReq(userId) { id =>
      getOrCreatePrefixFilter(id).map(Some(_))(ExecutionContext.fj)
    }
  }

  private[this] def topNWithInfos(infos:Seq[I], queryTerms:Array[String], limit:Option[Int])(implicit ord:Ordering[TypeaheadHit[I]]): Seq[TypeaheadHit[I]] = {
    if (queryTerms.length <= 0) Seq.empty else {
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
      top
    }
  }

  private[this] val consolidateBuildReq = new RequestConsolidator[Id[User], PrefixFilter[E]](10 minutes)

  protected def build(id: Id[User]): Future[PrefixFilter[E]] = {
    consolidateBuildReq(id){ id =>
      timing(s"buildFilter($id)") {
        val builder = new PrefixFilterBuilder[E]
        getAllInfosForUser(id).map { allInfos =>
          allInfos.foreach(info => builder.add(extractId(info), extractName(info)))
          val filter = builder.build
          log.info(s"[buildFilter($id)] allInfos(len=${allInfos.length})(${allInfos.take(10).mkString(",")}) filter.len=${filter.data.length}")
          filter
        }(ExecutionContext.fj)
      }
    }
  }

  def refresh(id: Id[User]): Future[PrefixFilter[E]] // slow

  def refreshByIds(userIds:Seq[Id[User]]):Future[Unit] = { // consider using zk and/or sqs to track progress
    implicit val prefix = LogPrefix(s"refreshByIds(#ids=${userIds.length})")
    implicit val fj = ExecutionContext.fj

    log.infoP(s"begin re-indexing users ...")
    FutureHelpers.sequentialExec(userIds) { userId =>
      refresh(userId) map { filter =>
        log.infoP(s"done with re-indexing ${userId}; filter=${filter}")
      }
    } map { _ =>
      log.infoP(s"done with re-indexing for ${userIds.length} users: ${userIds.take(3).toString} ... ${userIds.takeRight(3).toString}")
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

  implicit def format[I](implicit infoFormat: Format[I]): Format[TypeaheadHit[I]] = (
    (__ \ 'score).format[Int] and
    (__ \ 'name).format[String] and
    (__ \ 'ordinal).format[Int] and
    (__ \ 'info).format(infoFormat)
  )(TypeaheadHit.apply, unlift(TypeaheadHit.unapply))
}

case class TypeaheadHit[I](score: Int, name: String, ordinal: Int, info: I)
