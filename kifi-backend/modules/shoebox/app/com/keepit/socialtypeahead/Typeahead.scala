package com.keepit.socialtypeahead

import com.keepit.common.db.Id
import com.keepit.model.User
import scala.math.min

trait Typeahead[E, I] {

  protected def getPrefixFilter(userId: Id[User]): Option[PrefixFilter[E]]

  protected def getInfos(ids: Seq[Id[E]]): Seq[I]

  protected def extractName(info: I): String

  def search(userId: Id[User], query: String): Option[Seq[I]] = {
    if (query.trim.length > 0) {
      getPrefixFilter(userId).map{ filter =>
        val queryTerms = PrefixFilter.normalize(query).split("\\s+")
        getInfos(filter.filterBy(queryTerms)).map(info => (info, PrefixMatching.score(extractName(info), queryTerms)))
          .collect{ case (info, score) if score > 0.0f => (info, score) }.toSeq
          .sortBy(_._2)
          .map(_._1)
      }
    } else {
      None
    }
  }
}

object PrefixMatching {
  def score(nameString: String, query: String): Float = {
    score(nameString, PrefixFilter.normalize(query).split("\\s+").filter(_.length > 0))
  }

  private[this] def initScores(numTerms: Int): Array[Int] = {
    val scores = new Array[Int](numTerms + 1)
    var i = 0
    while (i <= numTerms) {
      scores(i) = i
      i += 1
    }
    scores
  }

  @inline private[this] def minScore(s1: Int, s2: Int, s3: Int) = min(min(s1, s2), s3)

  def score(nameString: String, queryTerms: Array[String]): Float = {
    val names = PrefixFilter.normalize(nameString).split("\\s+")
    println(s"""names=${names.mkString("[",",","]")} terms=${queryTerms.mkString("[",",","]")}""")
    var sc = 0;
    val scores = initScores(queryTerms.length)
    var matches = 1
    var i = 0
    println(scores.mkString("[", ",", "]"))
    while (i < names.length) {
      val name = names(i)
      var prev = scores(0)
      sc = prev + 1
      scores(0) = sc
      var j = 1
      while (j < scores.length) {
        val isMatch = name.startsWith(queryTerms(j - 1))
        sc = minScore(if (isMatch) { matches |= (1 << j); prev } else { prev + 1 },
                      if (j == queryTerms.length) scores(j) else scores(j) + 1,
                      sc)
        prev = scores(j)
        scores(j) = sc
        j += 1
      }
      println(scores.mkString("[", ",", "]"))
      i += 1
    }
    if (matches != ~(0xFFFFFFFF << (queryTerms.length + 1))) {
      println(s"match count=${Integer.bitCount(matches)-1}")
      sc = queryTerms.length
    }
    println(s"matches=$matches")
    sc.toFloat
  }
}

