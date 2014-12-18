package com.keepit.search.engine.uri

import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import com.keepit.search.engine.Visibility
import com.keepit.search.engine.explain.{ SearchExplanationBuilder, SearchExplanation, ScoreDetail }
import com.keepit.search.tracking.ResultClickBoosts
import org.apache.lucene.search.Query

case class UriSearchExplanation(
    id: Id[NormalizedURI],
    query: Query,
    labels: Array[String],
    matching: Float,
    matchingThreshold: Float,
    minMatchingThreshold: Float,
    clickBoostValue: Float,
    sharingBoostValue: Float,
    rawScore: Float,
    boostedScore: Float,
    scoreComputation: String,
    details: Map[String, Seq[ScoreDetail]]) extends SearchExplanation[NormalizedURI] {

  def boostValuesHtml(title: String): String = {
    val sb = new StringBuilder
    sb.append("<table>\n")
    sb.append(s"<tr> <th colspan=3> $title </th> </tr>\n")
    sb.append("<tr> <th> matching boost </th> <th> click boost </th> <th> sharing boost </th> </tr>\n")
    sb.append(s"<tr> <td> $matching </td> <td> $clickBoostValue </td> <td> $sharingBoostValue </td> </tr>\n")
    sb.append("</table>\n")

    sb.toString
  }

  def sharingHtml(title: String): String = {
    val sb = new StringBuilder

    def sharingCountByVisibility(visibility: Int): Unit = {
      // a record with no score is loaded for network information only
      val sharingCount = details(Visibility.name(visibility)).count { detail => detail.scoreMax.forall(_ == 0f) || (detail.visibility & Visibility.LIB_NAME_MATCH) != 0 }
      sb.append(s"<td> $sharingCount </td>")
    }
    def keepHitCountByVisibility(visibility: Int): Unit = {
      // a record with no score is loaded for network information only
      val sharingCount = details(Visibility.name(visibility)).count { detail => detail.scoreMax.forall(_ == 0f) || (detail.visibility & Visibility.LIB_NAME_MATCH) != 0 }
      sb.append(s"<td> ${details(Visibility.name(visibility)).size - sharingCount} </td>")
    }
    def libraryNameHitCountByVisibility(visibility: Int): Unit = {
      // a record with no score is loaded for network information only
      val count = details(Visibility.name(visibility)).count { detail => (detail.visibility & Visibility.LIB_NAME_MATCH) != 0 }
      sb.append(s"<td> $count </td>")
    }

    sb.append("<table>")
    sb.append(s"<tr><th colspan=4> $title </th></tr>\n")
    sb.append("<tr><th> </th><th> owner </th><th> member </th> <th> network </th></tr>\n")
    sb.append("<tr><th> sharing count </th>")
    sharingCountByVisibility(Visibility.OWNER)
    sharingCountByVisibility(Visibility.MEMBER)
    sharingCountByVisibility(Visibility.NETWORK)
    sb.append("</tr>")
    sb.append("<tr><th> keep hits (metadata) </th>")
    keepHitCountByVisibility(Visibility.OWNER)
    keepHitCountByVisibility(Visibility.MEMBER)
    keepHitCountByVisibility(Visibility.NETWORK)
    sb.append("</tr>")
    sb.append("</tr>")
    sb.append("<tr><th> keep hits (library name) </th>")
    libraryNameHitCountByVisibility(Visibility.OWNER)
    libraryNameHitCountByVisibility(Visibility.MEMBER)
    libraryNameHitCountByVisibility(Visibility.NETWORK)
    sb.append("</tr>")
    sb.append("</table>\n")

    sb.toString
  }
}

class UriSearchExplanationBuilder(uriId: Id[NormalizedURI], query: Query, labels: Array[String]) extends SearchExplanationBuilder[NormalizedURI](uriId, query, labels) {

  private[this] var _boostedScore: Float = -1f
  private[this] var _clickBoostValue: Float = -1f
  private[this] var _sharingBoostValue: Float = -1f

  def build() = {
    UriSearchExplanation(
      uriId,
      query,
      labels,
      matching,
      matchingThreshold,
      minMatchingThreshold,
      _clickBoostValue,
      _sharingBoostValue,
      rawScore,
      _boostedScore,
      scoreComputation,
      details
    )
  }

  def collectBoostedScore(id: Long, boostedScore: Float, clickBoostValue: Option[Float], sharingBoostValue: Option[Float]): Unit = {
    if (id == uriId.id) {
      _boostedScore = boostedScore
      clickBoostValue.foreach(_clickBoostValue = _)
      sharingBoostValue.foreach(_sharingBoostValue = _)
    }
  }

}

