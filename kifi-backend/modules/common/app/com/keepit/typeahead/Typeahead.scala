package com.keepit.typeahead

import com.keepit.common.concurrent.{ ExecutionContext, FutureHelpers }
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging.LoggerWithPrefix
import com.keepit.common.logging.{ LogPrefix, Logging }
import com.keepit.common.performance._
import com.keepit.common.service.RequestConsolidator
import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.keepit.common.core._

import scala.concurrent._
import scala.concurrent.duration._

trait PersonalTypeahead[T, E, I] {
  def ownerId: Id[T]
  def filter: PrefixFilter[E]
  def getInfos(infoIds: Seq[Id[E]]): Future[Seq[I]]
}

object PersonalTypeahead {
  def apply[T, E, I](id: Id[T], prefixFilter: PrefixFilter[E], infoGetter: Seq[Id[E]] => Future[Seq[I]]) = new PersonalTypeahead[T, E, I] {
    val ownerId = id
    val filter = prefixFilter
    def getInfos(infoIds: Seq[Id[E]]): Future[Seq[I]] = infoGetter(infoIds)
  }
}

trait Typeahead[T, E, I, P <: PersonalTypeahead[T, E, I]] extends Logging {

  protected val refreshRequestConsolidationWindow = 10 minutes

  protected val fetchRequestConsolidationWindow = 15 seconds

  protected def airbrake: AirbrakeNotifier

  protected def get(ownerId: Id[T]): Future[Option[P]]

  protected def create(ownerId: Id[T]): Future[P]

  protected def invalidate(typeahead: P): Unit

  protected def extractName(info: I): String

  protected def buildFilter(id: Id[T], allInfos: Seq[(Id[E], I)]): PrefixFilter[E] = {
    timing(s"buildFilter($id)") {
      val builder = new PrefixFilterBuilder[E]
      allInfos.foreach { case (infoId, info) => builder.add(infoId, extractName(info)) }
      val filter = builder.build
      log.info(s"[buildFilter($id)] allInfos(len=${allInfos.length})(${allInfos.take(10).mkString(",")}) filter.len=${filter.data.length}")
      filter
    }
  }

  def topN(ownerId: Id[T], query: String, limit: Option[Int])(implicit ord: Ordering[TypeaheadHit[I]]): Future[Seq[TypeaheadHit[I]]] = {
    if (query.trim.length <= 0) Future.successful(Seq.empty) else {
      implicit val fj = ExecutionContext.fj
      getOrElseCreate(ownerId) flatMap { typeahead =>
        if (typeahead.filter.isEmpty) {
          log.info(s"[asyncTopN($ownerId,$query)] filter is EMPTY")
          Future.successful(Seq.empty)
        } else {
          val queryTerms = PrefixFilter.normalize(query).split("\\s+")
          typeahead.getInfos(typeahead.filter.filterBy(queryTerms)).map { infos =>
            topNWithInfos(infos, queryTerms, limit)
          }
        }
      }
    }
  }

  private[this] val consolidateFetchReq = new RequestConsolidator[Id[T], P](fetchRequestConsolidationWindow)

  private[this] def getOrElseCreate(ownerId: Id[T]): Future[P] = {
    implicit val fj = ExecutionContext.fj
    consolidateFetchReq(ownerId) { id =>
      get(id).flatMap {
        case Some(typeahead) => Future.successful(typeahead)
        case None => doRefresh(id)
      }
    }
  }

  private[this] def topNWithInfos(infos: Seq[I], queryTerms: Array[String], limit: Option[Int])(implicit ord: Ordering[TypeaheadHit[I]]): Seq[TypeaheadHit[I]] = {
    if (queryTerms.length <= 0) Seq.empty else {
      var ordinal = 0
      val hits = infos.map { info =>
        ordinal += 1
        val name = PrefixFilter.normalize(extractName(info))
        TypeaheadHit(PrefixMatching.distanceWithNormalizedName(name, queryTerms), name, ordinal, info)
      }.collect {
        case elem @ TypeaheadHit(score, name, ordinal, info) if score < 1000000.0d => elem
      }.sorted
      val top = limit map (n => hits.take(n)) getOrElse hits
      top.foreach { s => log.info(s"[topN(${queryTerms.mkString(",")},$limit,#infos=${infos.length})] top=${top.mkString(",")}") }
      top
    }
  }

  private[this] val consolidateRefreshReq = new RequestConsolidator[Id[T], P](refreshRequestConsolidationWindow)

  private def doRefresh(ownerId: Id[T]): Future[P] = {
    implicit val fj = ExecutionContext.fj
    consolidateRefreshReq(ownerId) { id =>
      create(id).map { typeahead =>
        invalidate(typeahead)
        typeahead
      }
    }
  }

  def refresh(ownerId: Id[T]): Future[Unit] = {
    doRefresh(ownerId).imap(_ => Unit)
  }

  def refreshByIds(ownerIds: Seq[Id[T]]): Future[Unit] = { // consider using zk and/or sqs to track progress
    implicit val prefix = LogPrefix(s"refreshByIds(#ids=${ownerIds.length})")
    implicit val fj = ExecutionContext.fj

    log.infoP(s"begin re-indexing users ...")
    FutureHelpers.chunkySequentialExec(ownerIds) { ownerId =>
      refresh(ownerId) map { filter =>
        log.infoP(s"done with re-indexing ${ownerId}; filter=${filter}")
      }
    } map { _ =>
      log.infoP(s"done with re-indexing for ${ownerIds.length} users: ${ownerIds.take(3).toString} ... ${ownerIds.takeRight(3).toString}")
    }
  }
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

