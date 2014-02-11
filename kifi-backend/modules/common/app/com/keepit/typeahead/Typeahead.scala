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

  def search(userId: Id[User], query: String): Option[Seq[I]] = {
    if (query.trim.length > 0) {
      getPrefixFilter(userId).map{ filter =>
        val queryTerms = PrefixFilter.normalize(query).split("\\s+")
        var ordinal = 0
        getInfos(filter.filterBy(queryTerms)).map{ info =>
          ordinal += 1
          (info, PrefixMatching.distance(extractName(info), queryTerms).toDouble + 1.0d - (1.0d/ordinal))
        }.collect{ case (info, score) if score < 1000000.0d => (info, score) }.toSeq
        .sortBy(_._2)
        .map(_._1)
      }
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

