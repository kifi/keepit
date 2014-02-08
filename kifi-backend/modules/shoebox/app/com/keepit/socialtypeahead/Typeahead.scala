package com.keepit.socialtypeahead

import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.Id
import com.keepit.common.service.RequestConsolidator
import com.keepit.model.User
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent._
import scala.concurrent.duration._
import scala.math.min

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
        getInfos(filter.filterBy(queryTerms)).map(info => (info, PrefixMatching.distance(extractName(info), queryTerms)))
          .collect{ case (info, score) if score > 0.0f => (info, score) }.toSeq
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

object PrefixMatching {
  private[this] def initDistance(numTerms: Int): Array[Int] = {
    val scores = new Array[Int](numTerms + 1)
    var i = 0
    while (i <= numTerms) {
      scores(i) = i
      i += 1
    }
    scores
  }

  @inline private[this] def minScore(s1: Int, s2: Int, s3: Int) = min(min(s1, s2), s3)

  def distance(nameString: String, query: String): Int = {
    distance(nameString, PrefixFilter.tokenize(query))
  }

  def distance(nameString: String, queryTerms: Array[String]): Int = {
    val names = PrefixFilter.tokenize(nameString)
    val dists = initDistance(queryTerms.length)
    var sc = 0;
    var matchFlags = 1
    val allMatched =  ~(0xFFFFFFFF << (queryTerms.length + 1))
    val maxDist = names.length + queryTerms.length
    var i = 0
    while (i < names.length) {
      val name = names(i)
      var prev = dists(0)
      sc = prev + 1
      dists(0) = sc
      var j = 1
      while (j < dists.length) {
        val isMatch = name.startsWith(queryTerms(j - 1))
        sc = minScore(
          if (isMatch) {
            matchFlags |= (1 << j)
            prev
          } else {
            prev + i + j
          },
          if (j == queryTerms.length) {
            if (matchFlags == allMatched) dists(j) else maxDist
          } else {
            dists(j) + i + j
          },
          sc + 1
        )
        prev = dists(j)
        dists(j) = sc
        j += 1
      }
      i += 1
    }
    if (matchFlags != allMatched) sc = maxDist
    sc
  }
}

