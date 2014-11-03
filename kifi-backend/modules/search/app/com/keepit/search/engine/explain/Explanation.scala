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

  def queryHtml(title: String): String = {
    val sb = new StringBuilder
    sb.append("<table>\n")
    sb.append(s"<tr> <th> $title </th> </tr>\n")
    sb.append(s"<tr> <td> ${query.toString} </td> </tr>\n")
    sb.append("</table>\n")

    sb.toString
  }

  def scoreHtml(title: String): String = {
    val sb = new StringBuilder
    sb.append("<table>\n")
    sb.append(s"<tr> <th> $title </th> </tr>\n")
    sb.append(s"<tr> <td> $rawScore </td> </tr>\n")
    sb.append("</table>\n")

    sb.toString
  }

  def boostValuesHtml(title: String): String = {
    val sb = new StringBuilder
    sb.append("<table>\n")
    sb.append(s"<tr> <th colspan=2> $title </th> </tr>\n")
    sb.append("<tr> <th> click boost </th><th> sharing boost </th> </tr>\n")
    sb.append(s"<tr> <td> $clickBoostValue </td> <td> $sharingBoostValue </td> </tr>\n")
    sb.append("</table>\n")

    sb.toString
  }

  def sharingHtml(title: String): String = {
    val sb = new StringBuilder

    def sharingCountByVisibility(visibility: Int): Unit = {
      // a record with no score is loaded for network information only
      val count = details(Visibility.name(visibility)).count { detail => detail.scoreMax.forall(_ == 0f) }
      sb.append(s"<td> $count </td>")
    }
    def hitCountByVisibility(visibility: Int): Unit = {
      // a record with no score is loaded for network information only
      val count = details(Visibility.name(visibility)).count { detail => detail.scoreMax.forall(_ == 0f) }
      sb.append(s"<td> ${details(Visibility.name(visibility)).size - count} </td>")
    }

    sb.append("<table>")
    sb.append(s"<tr><th colspan=4> $title </th></tr>\n")
    sb.append("<tr><th> </th><th> owner </th><th> member </th> <th> network </th></tr>\n")
    sb.append("<tr><th> count </th>")
    sharingCountByVisibility(Visibility.OWNER)
    sharingCountByVisibility(Visibility.MEMBER)
    sharingCountByVisibility(Visibility.NETWORK)
    sb.append("</tr>")
    sb.append("<tr><th> hits </th>")
    hitCountByVisibility(Visibility.OWNER)
    hitCountByVisibility(Visibility.MEMBER)
    hitCountByVisibility(Visibility.NETWORK)
    sb.append("</tr>")
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
