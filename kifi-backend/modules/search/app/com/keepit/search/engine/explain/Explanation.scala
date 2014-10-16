package com.keepit.search.engine.explain

import com.keepit.search.engine.Visibility
import org.apache.lucene.search.Query

object Explanation {
  def apply(query: Query, labels: Array[String], details: Map[String, Seq[ScoreDetail]]) = {
    new Explanation(query, labels, details)
  }
}

class Explanation(val query: Query, val labels: Array[String], val details: Map[String, Seq[ScoreDetail]]) {
  def toHtml: String = {
    val sb = new StringBuilder

    def categoryByVisibility(visibility: Int): Unit = categoryByName(Visibility.name(visibility))

    def categoryByName(name: String): Unit = {
      val count = details(name).size
      if (count > 0) {
        sb.append(s"<th rowspan=$count> owner </th>\n")
        details(name).foreach { detail =>
          if (detail.scoreSum != null) {
            (detail.scoreMax zip detail.scoreSum).foreach { case (max, sum) => sb.append(s"<td> $max : $sum </td>") }
          } else {
            detail.scoreMax.foreach { value => sb.append(s"<td> $value </td>") }
          }
          sb.append('\n')
        }
      }
    }

    sb.append(s"""<table class="table table-bordered">\n""")

    // query labels
    sb.append("<th> </th>")
    labels.foreach { label => sb.append(s"""<th> $label </th>""") }

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
