package com.keepit.search.engine.explain

import com.keepit.search.engine.Visibility
import org.apache.commons.lang3.StringEscapeUtils
import org.apache.lucene.search.Query

object Explanation {
  def apply(query: Query, labels: Array[String], rawScore: Float, details: Map[String, Seq[ScoreDetail]], boostValues: (Float, Float)) = {
    new Explanation(query, labels, rawScore, details, boostValues._1, boostValues._2)
  }
}

class Explanation(val query: Query, val labels: Array[String], val rawScore: Float, val details: Map[String, Seq[ScoreDetail]], clickBoostValue: Float, sharingBoostValue: Float) {

  def boostValuesHtml: String = {
    val sb = new StringBuilder
    sb.append("<table>\n")
    sb.append("<tr><th> click boost </th><th> sharing boost </th> </tr>\n")
    sb.append(s"<tr> <td> $clickBoostValue </td> <td> $sharingBoostValue </td> </th></tr>\n")
    sb.append("</table>\n")

    sb.toString
  }

  def sharingHtml: String = {
    def sharingCountByVisibility(visibility: Int): Int = {
      // a record with no score is loaded for network information only
      details(Visibility.name(visibility)).count { detail => detail.scoreMax.forall(_ == 0f) }
    }

    val sb = new StringBuilder
    sb.append("<table>")
    sb.append("<tr><th> owner </th><th> member </th> <th> network </th></tr>\n")
    sb.append("<tr>")
    sb.append(s"<td> ${sharingCountByVisibility(Visibility.OWNER)} </td>")
    sb.append(s"<td> ${sharingCountByVisibility(Visibility.MEMBER)} </td>")
    sb.append(s"<td> ${sharingCountByVisibility(Visibility.NETWORK)} </td>")
    sb.append("</tr>")
    sb.append("</table>\n")

    sb.toString
  }

  def detailsHtml: String = {
    val sb = new StringBuilder

    def categoryByVisibility(visibility: Int): Unit = categoryByName(Visibility.name(visibility))

    def categoryByName(name: String): Unit = {
      val detailsWithScores = details(name).filter { detail => detail.scoreMax.exists(_ != 0f) }
      val count = detailsWithScores.size
      if (count > 0) {
        val nRows = detailsWithScores.map { detail => if (detail.scoreSum.isDefined) 2 else 1 }.sum
        detailsWithScores.headOption.foreach { detail =>
          sb.append("<tr> <th rowspan=$nRows> $name </th> <th> </th>\n")
          listScore(detail)
          detailsWithScores.tail.foreach { detail =>
            listScore(detail)
          }
        }
      }
    }

    def listScore(detail: ScoreDetail): Unit = {
      if (detail.scoreMax.exists(_ != 0f)) {
        sb.append("<tr> <td> max </td>")
        detail.scoreMax.foreach { value =>
          if (value == 0.0f) sb.append(s"<td> &nbsp; </td>")
          else sb.append(s"<td> $value </td>")
        }
        sb.append("</tr>\n")
        detail.scoreSum match {
          case Some(scoreSum) =>
            sb.append("<tr> <td> sum </td>")
            scoreSum.foreach { value =>
              if (value == 0.0f) sb.append(s"<td> &nbsp; </td>")
              else sb.append(s"<td> $value </td>")
            }
            sb.append("</tr>\n")
          case None =>
        }
      }
    }

    sb.append("""<table class="table table-bordered">\n""")

    // query labels
    sb.append("<tr> <th> </th> <th> </th>")
    labels.map(StringEscapeUtils.escapeHtml4(_)).foreach { label => sb.append(s"""<th> $label </th>""") }
    sb.append("</tr>\n")

    categoryByName("aggregate")
    categoryByVisibility(Visibility.OWNER)
    categoryByVisibility(Visibility.MEMBER)
    categoryByVisibility(Visibility.NETWORK)
    categoryByVisibility(Visibility.OTHERS)
    categoryByVisibility(Visibility.RESTRICTED)

    sb.append(s"""</table>\n""")

    sb.toString
  }
}
