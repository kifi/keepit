package com.keepit.curator.model

import com.keepit.common.crypto.ModelWithPublicId
import com.keepit.common.db.{ Id, Model, ModelWithState, State, States }
import com.keepit.common.time._
import com.keepit.model.{ Library, User }
import org.joda.time.DateTime

import play.api.libs.json._
import play.api.libs.functional.syntax._

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

case class LibraryScores(
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

  private def reducePrecision(x: Float): Float = {
    (x * 10000).toInt.toFloat * 10000
  }

  private def reducePrecision(xOpt: Option[Float]): Option[Float] = {
    xOpt.map { x => (x * 10000).toInt.toFloat / 10000 }
  }

  //this is used to save space in the json
  def withReducedPrecision(): LibraryScores = LibraryScores(
    reducePrecision(socialScore),
    reducePrecision(interestScore),
    reducePrecision(recencyScore),
    reducePrecision(popularityScore),
    reducePrecision(sizeScore),
    reducePrecision(contentScore)
  )
}

object LibraryScores {
  val oldFormat = Json.format[LibraryScores]

  def newFormat = (
    (__ \ 's).format[Float] and
    (__ \ 'i).format[Float] and
    (__ \ 'r).format[Float] and
    (__ \ 'p).format[Float] and
    (__ \ 'si).format[Float] and
    (__ \ 'c).formatNullable[Float]
  )(LibraryScores.apply, unlift(LibraryScores.unapply))

  implicit val format: Format[LibraryScores] = new Format[LibraryScores] {
    def reads(json: JsValue) = {
      oldFormat.reads(json) orElse newFormat.reads(json)
    }

    def writes(obj: LibraryScores): JsValue = {
      newFormat.writes(obj.withReducedPrecision)
    }
  }
}
