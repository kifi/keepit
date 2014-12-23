package com.keepit.search.engine.explain

import com.keepit.common.db.Id
import com.keepit.macros.Location
import com.keepit.search.Lang
import com.keepit.search.engine.{ ScoreContext, Visibility }
import com.keepit.search.util.join.DataBuffer
import org.apache.commons.lang3.StringEscapeUtils
import org.apache.lucene.search.Query
import play.api.libs.json.Json

import scala.collection.mutable.ListBuffer

trait SearchExplanation[T] {
  def id: Id[T]
  def query: String
  def labels: Array[String]
  def matching: Float
  def matchingThreshold: Float
  def minMatchingThreshold: Float
  def rawScore: Float
  def score: Float
  def scoreComputation: String
  def details: Seq[ScoreDetail]
  def lang: (Lang, Option[Lang])

  def queryHtml(title: String): String = {
    val sb = new StringBuilder
    sb.append("<table>\n")
    sb.append(s"<tr> <th> $title (Lang: ${lang._1.lang},${lang._2.map(_.lang).getOrElse("")})</th> </tr>\n")
    sb.append(s"""<tr> <td style="text-align:left"> $query </td> </tr>\n""")
    sb.append("</table>\n")

    sb.toString
  }

  def scoreHtml(title: String): String = {
    val sb = new StringBuilder
    sb.append("<table>\n")
    sb.append(s"<tr> <th colspan=2> $title </th> </tr>\n")
    sb.append(s"<tr> <th> raw score </th> <th> score </th> </tr>\n")
    sb.append(s"<tr> <td> $rawScore </td> <td> $score </td> </tr>\n")
    sb.append("</table>\n")

    sb.toString
  }

  def scoreComputationHtml(title: String): String = {
    val sb = new StringBuilder
    sb.append("<table>\n")
    sb.append(s"<tr> <th> $title </th> </tr>\n")
    sb.append("<tr> <td  style=\"text-align:left\">\n")
    sb.append("<ul>\n")
    sb.append(scoreComputation)
    sb.append("</ul>\n")
    sb.append("</td> </tr>\n")
    sb.append("</table>\n")

    sb.toString
  }

  def detailsHtml(title: String): String = {
    val sb = new StringBuilder

    def categoryBySourceAndVisibility(source: String, visibility: String, allDetails: Seq[ScoreDetail]): Unit = {

      val detailsWithScores = allDetails.filter { detail => detail.scoreMax.exists(_ != 0f) }
      val nRows = detailsWithScores.size
      if (nRows > 0) {
        sb.append(s"<tr> <th rowspan=$nRows> $source ($visibility) </th>")
        detailsWithScores.headOption.foreach { detail =>
          listScores(detail.scoreMax)
          sb.append("</tr>\n")
          detailsWithScores.tail.foreach { detail =>
            sb.append("<tr>")
            listScores(detail.scoreMax)
            sb.append("</tr>\n")
          }
        }
      }
    }

    def listScores(array: Array[Float]): Unit = {
      array.foreach { value =>
        if (value == 0.0f) sb.append(s"<td> &nbsp; </td>")
        else sb.append(s"<td> $value </td>")
      }
    }

    def aggregatedScores(): Unit = {
      val detailsWithScores = details.filter(detail => ScoreDetail.isFromCollector(detail.source))
      detailsWithScores.foreach(aggregatedScoreDetail(_))
    }

    def aggregatedScoreDetail(detail: ScoreDetail): Unit = {
      sb.append(s"<tr> <th> <u>max</u> (${detail.source}) </th>")
      listScores(detail.scoreMax)
      sb.append("</tr>\n")
      sb.append(s"<tr> <th> <u>sum</u> (${detail.source}) </th>")
      detail.scoreSum.foreach(listScores(_))
      sb.append("</tr>\n")
    }

    sb.append("<table>")
    sb.append("\n")

    // title
    sb.append(s"<tr> <th colspan=${1 + labels.length}> $title </th> </tr>\n")

    // query labels
    sb.append("<tr> <th> </th>")
    labels.map(StringEscapeUtils.escapeHtml4(_)).foreach { label => sb.append(s"<th> $label </th>") }
    sb.append("</tr>\n")

    aggregatedScores()
    details.groupBy(_.source).collect {
      case (source, sourceDetails) if !ScoreDetail.isFromCollector(source) =>
        sourceDetails.groupBy(detail => Visibility.name(detail.visibility)).map {
          case (visibility, sourceAndVisibilityDetails) =>
            categoryBySourceAndVisibility(source, visibility, sourceAndVisibilityDetails)
        }
    }
    sb.append(s"""</table>\n""")

    sb.toString
  }
}

abstract class SearchExplanationBuilder[T](val resultId: Id[T], val lang: (Lang, Option[Lang]), val query: Query, val labels: Array[String]) {

  def build(): SearchExplanation[T]

  private[this] var _matchingThreshold: Float = -1f
  private[this] var _minMatchingThreshold: Float = -1f
  private[this] var _matching: Float = -1f
  private[this] var _rawScore: Float = -1f
  private[this] var _scoreComputation: String = ""

  private[this] val _details = new ListBuffer[ScoreDetail]()

  def collectBufferScoreContribution(primaryId: Long, secondaryId: Long, visibility: Int, taggedScores: Array[Int], numberOfTaggedScores: Int)(implicit location: Location): Unit = {
    if (primaryId == resultId.id) {
      val scoreArray = Array.fill(taggedScores.size)(0.0f)
      taggedScores.take(numberOfTaggedScores).foreach { bits =>
        val idx = DataBuffer.getTaggedFloatTag(bits)
        val scr = DataBuffer.getTaggedFloatValue(bits)
        scoreArray(idx) = scr
      }
      _details += new ScoreDetail(ScoreDetail.sourceFromLocation(location), primaryId, secondaryId, visibility, scoreArray, None)
    }
  }

  def collectDirectScoreContribution(primaryId: Long, secondaryId: Long, visibility: Int, scoreArray: Array[Float])(implicit location: Location): Unit = {
    if (primaryId == resultId.id) {
      _details += new ScoreDetail(ScoreDetail.sourceFromLocation(location), primaryId, secondaryId, visibility, scoreArray.clone(), None)
    }
  }

  def collectRawScore(ctx: ScoreContext, matchingThreshold: Float, minMatchingThreshold: Float)(implicit location: Location): Unit = {
    if (ctx.id == resultId.id) {
      // compute the matching value. this returns 0.0f if the match is less than the MIN_PERCENT_MATCH
      _matchingThreshold = matchingThreshold
      _minMatchingThreshold = minMatchingThreshold
      _matching = ctx.computeMatching(minMatchingThreshold)
      _rawScore = ctx.score()
      _scoreComputation = ctx.explainScoreExpr()
      _details += ScoreDetail.aggregate(ScoreDetail.sourceFromLocation(location), ctx)
    }
  }

  def matching: Float = _matching
  def matchingThreshold: Float = _matchingThreshold
  def minMatchingThreshold = _minMatchingThreshold
  def rawScore: Float = _rawScore
  def scoreComputation: String = _scoreComputation
  def details: Seq[ScoreDetail] = _details.toSeq

}

case class ScoreDetail(source: String, primaryId: Long, secondaryId: Long, visibility: Int, scoreMax: Array[Float], scoreSum: Option[Array[Float]])

object ScoreDetail {
  def aggregate(source: String, ctx: ScoreContext) = {
    require(isFromCollector(source), "Aggregated scores should be captured from the collector.")
    new ScoreDetail(source, ctx.id, ctx.secondaryId, ctx.visibility, ctx.scoreMax.clone, Some(ctx.scoreSum.clone))
  }

  def isFromCollector(source: String) = source.contains("Collector")

  def sourceFromLocation(location: Location): String = location.className.stripPrefix("class").stripSuffix("ScoreVectorSource").trim

  implicit val format = Json.format[ScoreDetail]
}

