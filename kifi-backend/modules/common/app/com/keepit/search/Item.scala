package com.keepit.search

import com.keepit.common.db.Id
import com.keepit.model.{ Hashtag, User, Library, NormalizedURI }
import play.api.libs.json._
import com.keepit.serializer.TupleFormat

case class Item(uri: Id[NormalizedURI], keptIn: Option[Id[Library]])

object Item {
  implicit val format = Json.format[Item]
  implicit def itemMapFormat[T](implicit tFormat: Format[T]) = {
    implicit val tupleFormat = TupleFormat.tuple2Format[Item, T]
    new Format[Map[Item, T]] {
      def reads(json: JsValue) = json.validate[Seq[(Item, T)]].map(_.toMap)
      def writes(itemMap: Map[Item, T]) = Json.toJson(itemMap.toSeq)
    }
  }
}

case class AugmentedItem(
  uri: Id[NormalizedURI],
  kept: Option[(Id[Library], Option[Id[User]], Seq[Hashtag])],
  moreKeeps: Seq[(Option[Id[Library]], Option[Id[User]])],
  moreTags: Seq[Hashtag])

object AugmentedItem {
  implicit val format = {
    implicit val keptFormat = TupleFormat.tuple3Format[Id[Library], Option[Id[User]], Seq[Hashtag]]
    implicit val moreKeepsFormat = TupleFormat.tuple2Format[Option[Id[Library]], Option[Id[User]]]
    Json.format[AugmentedItem]
  }
}

case class RestrictedKeepInfo(keptIn: Option[Id[Library]], keptBy: Option[Id[User]], tags: Set[Hashtag])

object RestrictedKeepInfo {
  implicit val format = new Format[RestrictedKeepInfo] {
    def reads(json: JsValue) = json.validate[Seq[JsValue]].map {
      case Seq(keptIn, keptBy, tags) =>
        RestrictedKeepInfo(keptIn.as[Option[Id[Library]]], keptBy.as[Option[Id[User]]], tags.as[Set[Hashtag]])
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
    tagScores: Map[Hashtag, Float]) {

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

case class AugmentationContext(corpus: Map[Item, Float], keptIn: Option[Set[Id[Library]]], keptBy: Option[Set[Id[User]]])

object AugmentationContext {
  implicit val format = Json.format[AugmentationContext]
  def uniform(items: Seq[Item]): AugmentationContext = AugmentationContext(items.map(_ -> 1f).toMap, None, None)
}

object ContextualAugmentationScores {
  implicit val format = Json.format[ContextualAugmentationScores]
  val empty = ContextualAugmentationScores(Map.empty, Map.empty, Map.empty)
}

case class ItemAugmentationRequest(
  userId: Id[User],
  items: Set[Item],
  context: AugmentationContext)

object ItemAugmentationRequest {
  implicit val format = Json.format[ItemAugmentationRequest]
}

case class ItemAugmentationResponse(infos: Map[Item, AugmentationInfo], scores: ContextualAugmentationScores)

object ItemAugmentationResponse {
  implicit val format = Json.format[ItemAugmentationResponse]
  val empty = ItemAugmentationResponse(Map.empty, ContextualAugmentationScores.empty)
}
