package com.keepit.search.engine.library

import com.keepit.common.db.Id
import com.keepit.common.json.TupleFormat
import com.keepit.model.Library
import com.keepit.search.Lang
import com.keepit.search.engine.explain.{ SearchExplanationBuilder, SearchExplanation, ScoreDetail }
import org.apache.lucene.search.Query
import play.api.libs.json.Json

case class LibrarySearchExplanation(
    id: Id[Library],
    query: String,
    labels: Array[String],
    lang: (Lang, Option[Lang]),
    matching: Float,
    matchingThreshold: Float,
    minMatchingThreshold: Float,
    myLibraryBoost: Float,
    rawScore: Float,
    score: Float,
    scoreComputation: String,
    details: Seq[ScoreDetail]) extends SearchExplanation[Library] {

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

object LibrarySearchExplanation {
  implicit val format = {
    implicit val langFormat = TupleFormat.tuple2Format[Lang, Option[Lang]]
    Json.format[LibrarySearchExplanation]
  }
}

class LibrarySearchExplanationBuilder(libraryId: Id[Library], lang: (Lang, Option[Lang]), query: Query, labels: Array[String]) extends SearchExplanationBuilder[Library](libraryId, lang, query, labels) {

  private[this] var _score: Float = -1f
  private[this] var _myLibraryBoostValue: Float = -1f

  def build() = {
    LibrarySearchExplanation(
      libraryId,
      query.toString,
      labels,
      lang,
      matching,
      matchingThreshold,
      minMatchingThreshold,
      _myLibraryBoostValue,
      rawScore,
      _score,
      scoreComputation,
      details
    )
  }

  def collectScore(id: Long, score: Float, myLibraryBoost: Float): Unit = {
    if (id == libraryId.id) {
      _score = score
      _myLibraryBoostValue = myLibraryBoost
    }
  }

}