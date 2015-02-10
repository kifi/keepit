package com.keepit.search.engine.user

import com.keepit.common.db.Id
import com.keepit.common.json.TupleFormat
import com.keepit.model.User
import com.keepit.search.Lang
import com.keepit.search.engine.explain.{ SearchExplanationBuilder, SearchExplanation, ScoreDetail }
import org.apache.lucene.search.Query
import play.api.libs.json.Json

case class UserSearchExplanation(
    id: Id[User],
    query: String,
    labels: Array[String],
    lang: (Lang, Option[Lang]),
    matching: Float,
    matchingThreshold: Float,
    minMatchingThreshold: Float,
    myFriendBoost: Float,
    rawScore: Float,
    score: Float,
    scoreComputation: String,
    details: Seq[ScoreDetail]) extends SearchExplanation[User] {

  def boostValuesHtml(title: String): String = {
    val sb = new StringBuilder
    sb.append("<table>\n")
    sb.append(s"<tr> <th colspan=3> $title </th> </tr>\n")
    sb.append("<tr> <th> matching boost </th> <th> myFriend boost </th> </tr>\n")
    sb.append(s"<tr> <td> $matching </td> <td> $myFriendBoost </td> </tr>\n")
    sb.append("</table>\n")

    sb.toString
  }
}

object UserSearchExplanation {
  implicit val format = {
    implicit val langFormat = TupleFormat.tuple2Format[Lang, Option[Lang]]
    Json.format[UserSearchExplanation]
  }
}

class UserSearchExplanationBuilder(userId: Id[User], lang: (Lang, Option[Lang]), query: Query, labels: Array[String]) extends SearchExplanationBuilder[User](userId, lang, query, labels) {

  private[this] var _score: Float = -1f
  private[this] var _myFriendBoostValue: Float = -1f

  def build() = {
    UserSearchExplanation(
      userId,
      query.toString,
      labels,
      lang,
      matching,
      matchingThreshold,
      minMatchingThreshold,
      _myFriendBoostValue,
      rawScore,
      _score,
      scoreComputation,
      details
    )
  }

  def collectScore(id: Long, score: Float, myUserBoost: Float): Unit = {
    if (id == userId.id) {
      _score = score
      _myFriendBoostValue = myUserBoost
    }
  }

}