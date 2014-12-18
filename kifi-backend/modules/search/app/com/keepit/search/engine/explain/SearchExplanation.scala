package com.keepit.search.engine.explain

import com.keepit.common.db.Id
import com.keepit.search.engine.{ ScoreContext, Visibility }
import org.apache.commons.lang3.StringEscapeUtils
import org.apache.lucene.search.Query

import scala.collection.mutable.ListBuffer

trait SearchExplanation[T] {
  def id: Id[T]
  def query: Query
  def labels: Array[String]
  def matching: Float
  def matchingThreshold: Float
  def minMatchingThreshold: Float
  def rawScore: Float
  def boostedScore: Float
  def scoreComputation: String
  def details: Map[String, Seq[ScoreDetail]]

  def queryHtml(title: String): String = {
    val sb = new StringBuilder
    sb.append("<table>\n")
    sb.append(s"<tr> <th> $title </th> </tr>\n")
    sb.append(s"""<tr> <td style="text-align:left"> ${query.toString} </td> </tr>\n""")
    sb.append("</table>\n")

    sb.toString
  }

  def scoreHtml(title: String): String = {
    val sb = new StringBuilder
    sb.append("<table>\n")
    sb.append(s"<tr> <th colspan=2> $title </th> </tr>\n")
    sb.append(s"<tr> <th> raw score </th> <th> boosted score </th> </tr>\n")
    sb.append(s"<tr> <td> $rawScore </td> <td> $boostedScore </td> </tr>\n")
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

    def categoryByVisibility(visibility: Int): Unit = {
      val name = Visibility.name(visibility)
      val source = if ((visibility & (Visibility.OWNER | Visibility.MEMBER | Visibility.NETWORK)) != 0) {
        s"keep ($name)"
      } else if ((visibility & Visibility.OTHERS) != 0) {
        "article"
      } else {
        "restricted"
      }

      val detailsWithScores = details(name).filter { detail => detail.scoreMax.exists(_ != 0f) }
      val nRows = detailsWithScores.size
      if (nRows > 0) {
        sb.append(s"<tr> <th rowspan=$nRows> $source </th>")
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
      val detailsWithScores = details("aggregate")
      detailsWithScores.foreach(aggregatedScoreDetail(_))
    }

    def aggregatedScoreDetail(detail: ScoreDetail): Unit = {
      sb.append(s"<tr> <th> <u>max</u> </th>")
      listScores(detail.scoreMax)
      sb.append("</tr>\n")
      sb.append("<tr> <th> <u>sum</u> </th>")
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
    categoryByVisibility(Visibility.OWNER)
    categoryByVisibility(Visibility.MEMBER)
    categoryByVisibility(Visibility.NETWORK)
    categoryByVisibility(Visibility.OTHERS)
    categoryByVisibility(Visibility.RESTRICTED)

    sb.append(s"""</table>\n""")

    sb.toString
  }
}

abstract class SearchExplanationBuilder[T](val resultId: Id[T], val query: Query, val labels: Array[String]) {

  def build(): SearchExplanation[T]

  private[this] var _matchingThreshold: Float = -1f
  private[this] var _minMatchingThreshold: Float = -1f
  private[this] var _matching: Float = -1f
  private[this] var _rawScore: Float = -1f
  private[this] var _scoreComputation: String = ""

  private[this] val _details: Map[String, ListBuffer[ScoreDetail]] = Map(
    "aggregate" -> new ListBuffer[ScoreDetail](),
    Visibility.name(Visibility.OWNER) -> new ListBuffer[ScoreDetail](),
    Visibility.name(Visibility.MEMBER) -> new ListBuffer[ScoreDetail](),
    Visibility.name(Visibility.NETWORK) -> new ListBuffer[ScoreDetail](),
    Visibility.name(Visibility.OTHERS) -> new ListBuffer[ScoreDetail](),
    Visibility.name(Visibility.RESTRICTED) -> new ListBuffer[ScoreDetail]()
  )

  def collectScoreContribution(primaryId: Long, secondaryId: Long, visibility: Int, scoreArray: Array[Float]): Unit = {
    if (primaryId == resultId.id) {
      _details(Visibility.name(visibility)) += ScoreDetail(primaryId, secondaryId, visibility, scoreArray.clone)
    }
  }

  def collectRawScore(ctx: ScoreContext, matchingThreshold: Float, minMatchingThreshold: Float): Unit = {
    if (ctx.id == resultId.id) {
      // compute the matching value. this returns 0.0f if the match is less than the MIN_PERCENT_MATCH
      _matchingThreshold = matchingThreshold
      _minMatchingThreshold = minMatchingThreshold
      _matching = ctx.computeMatching(minMatchingThreshold)
      _rawScore = ctx.score()
      _scoreComputation = ctx.explainScoreExpr()
      _details("aggregate") += ScoreDetail(ctx)
    }
  }

  def matching: Float = _matching
  def matchingThreshold: Float = _matchingThreshold
  def minMatchingThreshold = _minMatchingThreshold
  def rawScore: Float = _rawScore
  def scoreComputation: String = _scoreComputation
  def details: Map[String, Seq[ScoreDetail]] = _details.mapValues(_.toSeq)
}