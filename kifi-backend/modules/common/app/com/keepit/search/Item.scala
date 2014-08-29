package com.keepit.search

import com.keepit.common.db.Id
import com.keepit.model.{ User, Library, NormalizedURI }
import play.api.libs.json._
import com.keepit.search.Item.Tag
import com.keepit.serializer.TupleFormat

object Item {
  type Tag = String
  implicit val format = Json.format[Item]
  implicit def itemMapFormat[T](implicit tFormat: Format[T]) = {
    implicit val tupleFormat = TupleFormat.tuple2Format[Item, T]
    new Format[Map[Item, T]] {
      def reads(json: JsValue) = json.validate[Seq[(Item, T)]].map(_.toMap)
      def writes(itemMap: Map[Item, T]) = Json.toJson(itemMap.toSeq)
    }
  }
}

case class Item(uri: Id[NormalizedURI], keptIn: Option[Id[Library]])

case class AugmentedItem(
  uri: Id[NormalizedURI],
  kept: Option[(Id[Library], Option[Id[User]], Seq[Tag])],
  moreKeeps: Seq[(Option[Id[Library]], Option[Id[User]])],
  moreTags: Seq[Tag])

case class RestrictedKeepInfo(keptIn: Option[Id[Library]], keptBy: Option[Id[User]], tags: Seq[Tag])

object RestrictedKeepInfo {
  implicit val format = new Format[RestrictedKeepInfo] {
    def reads(json: JsValue) = json.validate[Seq[JsValue]].map {
      case Seq(keptIn, keptBy, tags) =>
        RestrictedKeepInfo(keptIn.as[Option[Id[Library]]], keptBy.as[Option[Id[User]]], tags.as[Seq[Tag]])
    }
    def writes(keep: RestrictedKeepInfo) = Json.arr(keep.keptIn, keep.keptBy, keep.tags)
  }
}

case class AugmentationInfo(keeps: Seq[RestrictedKeepInfo])
object AugmentationInfo {
  implicit val format = Json.format[AugmentationInfo]
}

case class ContextualAugmentationScores(
    libraryScores: Map[Id[Library], Float],
    userScores: Map[Id[User], Float],
    tagScores: Map[Tag, Float]) {

  def merge(moreScores: ContextualAugmentationScores): ContextualAugmentationScores = {
    val addedLibraryScores = (libraryScores.keySet ++ moreScores.libraryScores.keySet).map { id =>
      id -> (libraryScores.getOrElse(id, 0f) + moreScores.libraryScores.getOrElse(id, 0f))
    }.toMap
    val addedUserScores = (userScores.keySet ++ moreScores.userScores.keySet).map { id =>
      id -> (userScores.getOrElse(id, 0f) + moreScores.userScores.getOrElse(id, 0f))
    }.toMap
    val addedTagScores = (tagScores.keySet ++ moreScores.tagScores.keySet).map { id =>
      id -> (tagScores.getOrElse(id, 0f) + moreScores.tagScores.getOrElse(id, 0f))
    }.toMap
    ContextualAugmentationScores(addedLibraryScores, addedUserScores, addedTagScores)
  }
}

object ContextualAugmentationScores {
  implicit val format = Json.format[ContextualAugmentationScores]
}

case class ItemAugmentationRequest(
  userId: Id[User],
  keptIn: Set[Id[Library]],
  keptBy: Set[Id[User]],
  items: Set[Item],
  context: Map[Item, Float])

object ItemAugmentationRequest {
  implicit val format = Json.format[ItemAugmentationRequest]
}

case class ItemAugmentationResponse(infos: Map[Item, AugmentationInfo], scores: ContextualAugmentationScores)

object ItemAugmentationResponse {
  implicit val format = Json.format[ItemAugmentationResponse]
}
