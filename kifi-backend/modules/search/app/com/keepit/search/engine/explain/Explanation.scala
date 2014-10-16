package com.keepit.search.engine.explain

import com.keepit.search.engine.Visibility
import org.apache.commons.lang3.StringEscapeUtils
import org.apache.lucene.search.Query

object Explanation {
  def apply(query: Query, labels: Array[String], details: Map[String, Seq[ScoreDetail]], boostValues: (Float, Float)) = {
    new Explanation(query, labels, details, boostValues._1, boostValues._2)
  }
}

class Explanation(val query: Query, val labels: Array[String], val details: Map[String, Seq[ScoreDetail]], clickBoostValue: Float, sharingBoostValue: Float) {

  def boostValuesHtml: String = {
    val sb = new StringBuilder
    sb.append("<table>\n")
    sb.append("<tr><th> click boost </th><th> sharing boost </th> </tr>\n")
    sb.append(s"<tr> <td> $clickBoostValue </td> <td> $sharingBoostValue </td> </th></tr>\n")
    sb.append("</table>\n")

    sb.toString
  }

  def detailsHtml: String = {
    val sb = new StringBuilder

    def categoryByVisibility(visibility: Int): Unit = categoryByName(Visibility.name(visibility))

    def categoryByName(name: String): Unit = {
      val count = details(name).size
      if (count > 0) {
        details(name).headOption.foreach { detail =>
          sb.append("<tr>")
          sb.append(s"<th rowspan=$count> $name </th>\n")
          listScore(detail)
          sb.append("</tr>\n")
        }
        details(name).tail.foreach { detail =>
          sb.append("<tr>")
          listScore(detail)
          sb.append("</tr>\n")
        }
      }
    }

    def listScore(detail: ScoreDetail): Unit = {
      detail.scoreSum match {
        case Some(scoreSum) =>
          (detail.scoreMax zip scoreSum).foreach { case (max, sum) => sb.append(s"<td> $max : $sum </td>") }
        case None =>
          detail.scoreMax.foreach { value => sb.append(s"<td> $value </td>") }
      }

    }

    sb.append(s"""<table class="table table-bordered">\n""")

    // query labels
    sb.append("<tr> <th> </th>")
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
