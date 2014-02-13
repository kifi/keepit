package com.keepit.typeahead

import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.Id
import com.keepit.common.service.RequestConsolidator
import com.keepit.model.User
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent._
import scala.concurrent.duration._

trait Typeahead[E, I] {

  val store: PrefixFilterStore[User]

  protected val consolidateBuildReq = new RequestConsolidator[Id[User], PrefixFilter[E]](10 minutes)

  protected def getPrefixFilter(userId: Id[User]): Option[PrefixFilter[E]]

  protected def getInfos(ids: Seq[Id[E]]): Seq[I]

  protected def getAllInfosForUser(id: Id[User]): Seq[I]

  protected def extractId(info: I): Id[E]

  protected def extractName(info: I): String

  def search(userId: Id[User], query: String)(implicit ord: Ordering[TypeaheadHit[I]]): Option[Seq[I]] = {
    if (query.trim.length > 0) {
      getPrefixFilter(userId).flatMap{ filter =>
        val queryTerms = PrefixFilter.normalize(query).split("\\s+")
        search(getInfos(filter.filterBy(queryTerms)), queryTerms)
      }
    } else {
      None
    }
  }

  def search(infos: Seq[I], queryTerms: Array[String])(implicit ord: Ordering[TypeaheadHit[I]]): Option[Seq[I]] = {
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
        val builder = new PrefixFilterBuilder[E]
        getAllInfosForUser(id).foreach(info => builder.add(extractId(info), extractName(info)))
        val filter = builder.build
        store += (id -> filter.data)
        filter
      }
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
}

case class TypeaheadHit[I](score: Int, name: String, ordinal: Int, info: I)

