package com.keepit.curator.model

import com.keepit.common.crypto.ModelWithPublicId
import com.keepit.common.db.{ Id, Model, ModelWithState, State, States }
import com.keepit.common.time._
import com.keepit.model.{ Library, User }
import com.kifi.macros.json
import org.joda.time.DateTime

object LibraryRecommendation {
  implicit def toLibraryRecoInfo(libReco: LibraryRecommendation): LibraryRecoInfo = {
    LibraryRecoInfo(
      libraryId = libReco.libraryId,
      userId = libReco.userId,
      masterScore = libReco.masterScore,
      explain = libReco.allScores.toString
    )
  }
}

case class LibraryRecommendation(
    id: Option[Id[LibraryRecommendation]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[LibraryRecommendation] = LibraryRecommendationStates.ACTIVE,
    libraryId: Id[Library],
    userId: Id[User],
    masterScore: Float,
    allScores: LibraryScores,
    followed: Boolean = false,
    delivered: Int = 0,
    clicked: Int = 0,
    trashed: Boolean = false,
    vote: Option[Boolean] = None) extends Model[LibraryRecommendation] with ModelWithPublicId[LibraryRecommendation] with ModelWithState[LibraryRecommendation] {

  def withId(id: Id[LibraryRecommendation]): LibraryRecommendation = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime): LibraryRecommendation = this.copy(updatedAt = updateTime)
}

object LibraryRecommendationStates extends States[LibraryRecommendation]

@json case class LibraryScores(
    socialScore: Float,
    interestScore: Float,
    recencyScore: Float,
    popularityScore: Float,
    sizeScore: Float,
    contentScore: Option[Float]) {

  val contentScoreOrDefault: Float = contentScore.getOrElse(1f)

  override def toString() =
    f"""
       |s:$socialScore%1.2f-
       |i:$interestScore%1.2f-
       |r:$recencyScore%1.2f-
       |p:$popularityScore%1.2f-
       |si:$sizeScore%1.2f-
       |c:$contentScoreOrDefault%1.2f
     """.stripMargin.replace("\n", "").trim()
}
