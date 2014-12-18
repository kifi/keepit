package com.keepit.search.engine.library

import com.keepit.common.db.Id
import com.keepit.model.Library
import com.keepit.search.engine.explain.{ SearchExplanationBuilder, SearchExplanation, ScoreDetail }
import org.apache.lucene.search.Query

case class LibrarySearchExplanation(
    id: Id[Library],
    query: Query,
    labels: Array[String],
    matching: Float,
    matchingThreshold: Float,
    minMatchingThreshold: Float,
    myLibraryBoost: Float,
    rawScore: Float,
    boostedScore: Float,
    scoreComputation: String,
    details: Map[String, Seq[ScoreDetail]]) extends SearchExplanation[Library] {

  def boostValuesHtml(title: String): String = {
    val sb = new StringBuilder
    sb.append("<table>\n")
    sb.append(s"<tr> <th colspan=3> $title </th> </tr>\n")
    sb.append("<tr> <th> matching boost </th> <th> myLibrary boost </th> </tr>\n")
    sb.append(s"<tr> <td> $matching </td> <td> $myLibraryBoost </td> </tr>\n")
    sb.append("</table>\n")

    sb.toString
  }
}

class LibrarySearchExplanationBuilder(libraryId: Id[Library], query: Query, labels: Array[String]) extends SearchExplanationBuilder[Library](libraryId, query, labels) {

  private[this] var _boostedScore: Float = -1f
  private[this] var _myLibraryBoostValue: Float = -1f

  def build() = {
    LibrarySearchExplanation(
      libraryId,
      query,
      labels,
      matching,
      matchingThreshold,
      minMatchingThreshold,
      _myLibraryBoostValue,
      rawScore,
      _boostedScore,
      scoreComputation,
      details
    )
  }

  def collectBoostedScore(id: Long, boostedScore: Float, myLibraryBoost: Float): Unit = {
    if (id == libraryId.id) {
      _boostedScore = boostedScore
      _myLibraryBoostValue = myLibraryBoost
    }
  }

}