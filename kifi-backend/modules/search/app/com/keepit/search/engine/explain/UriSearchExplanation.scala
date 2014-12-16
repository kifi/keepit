package com.keepit.search.engine.explain

import com.keepit.search.engine.Visibility
import org.apache.commons.lang3.StringEscapeUtils
import org.apache.lucene.search.Query

trait SearchExplanation {
  val query: Query
  val labels: Array[String]
  val matching: Float
  val matchingThreshold: Float
  val minMatchingThreshold: Float
  val rawScore: Float
  val boostedScore: Float
  val scoreComputation: String
  val details: Map[String, Seq[ScoreDetail]]

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
