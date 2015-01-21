package com.keepit.typeahead

import scala.math.min
import com.keepit.common.logging.Logging
import scala.util.matching.Regex
import java.util.regex.Pattern

object PrefixMatching extends Logging {

  val maxDist = Int.MaxValue

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
    distance(PrefixFilter.tokenize(nameString), queryTerms)
  }

  def distanceWithNormalizedName(nameString: String, queryTerms: Array[String]): Int = {
    distance(PrefixFilter.tokenizeNormalizedName(nameString), queryTerms)
  }

  def distance(names: Array[String], queryTerms: Array[String]): Int = {
    // this code is performance critical, intentionally written in non-functional style
    val dists = initDistance(queryTerms.length)
    var sc = 0;
    var matchFlags = 1
    val allMatched = ~(0xFFFFFFFF << (queryTerms.length + 1))
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

  private def doTheyAlign(firstString: String, secondString: String): Boolean = {
    val firstTokens = PrefixFilter.tokenizeNormalizedName(firstString)
    val secondTokens = PrefixFilter.tokenizeNormalizedName(secondString)
    (firstTokens.length == secondTokens.length) &&
      (firstTokens zip secondTokens).forall { case (firstToken, secondToken) => firstToken.length == secondToken.length }
  }

  def getHighlightingRegex(query: String): Regex = {
    val queryTerms = PrefixFilter.tokenize(query)
    ("(^|" + PrefixFilter.tokenBoundary + ")(" + queryTerms.sortBy(-_.length).map(Pattern.quote).mkString("|") + ")").r
  }

  def highlight(name: String, highlightingRegex: Regex): List[(Int, Int)] = {
    // This requires that the normalization does not shift characters (e.g. currently only removes diacritical marks)
    val normalizedName = PrefixFilter.normalize(name)
    if (doTheyAlign(name, normalizedName)) {
      highlightingRegex.findAllMatchIn(normalizedName).map { m => (m.start(2), m.group(2).length) }.toList
    } else {
      log.warn(s"PrefixFilter normalization has not preserved alignment from $name to $normalizedName")
      Nil
    }
  }

  def highlight(name: String, query: String): List[(Int, Int)] = highlight(name, getHighlightingRegex(query))
}

