package com.keepit.curator.model

import com.keepit.common.crypto.ModelWithPublicId
import com.keepit.common.db.{ Id, Model, ModelWithState, State, States }
import com.keepit.common.time._
import com.keepit.model.{ Library, User }
import com.kifi.macros.json
import org.joda.time.DateTime

case class LibraryRecommendation(
    id: Option[Id[LibraryRecommendation]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[LibraryRecommendation] = LibraryRecommendationStates.ACTIVE,
    libraryId: Id[Library],
    userId: Id[User],
    masterScore: Float,
    allScores: LibraryScores,
    followed: Boolean = false) extends Model[LibraryRecommendation] with ModelWithPublicId[LibraryRecommendation] with ModelWithState[LibraryRecommendation] {

  def withId(id: Id[LibraryRecommendation]): LibraryRecommendation = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime): LibraryRecommendation = this.copy(updatedAt = updateTime)
}

object LibraryRecommendationStates extends States[LibraryRecommendation]

@json case class LibraryScores(
    socialScore: Float,
    interestScore: Float,
    recencyScore: Float,
    popularityScore: Float,
    sizeScore: Float) {

  override def toString() =
    f"""
       |s:$socialScore%1.2f-
       |i:$interestScore%1.2f-
       |r:$recencyScore%1.2f-
       |p:$popularityScore%1.2f-
       |sz:$sizeScore%1.2f
     """.stripMargin.replace("\n", "").trim()
}
