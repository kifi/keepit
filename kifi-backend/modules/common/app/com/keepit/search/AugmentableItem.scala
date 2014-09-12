package com.keepit.search

import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.model._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import com.keepit.serializer.TupleFormat

case class AugmentableItem(uri: Id[NormalizedURI], keptIn: Option[Id[Library]])

object AugmentableItem {
  implicit val format = Json.format[AugmentableItem]
  implicit def itemMapFormat[T](implicit tFormat: Format[T]) = {
    implicit val tupleFormat = TupleFormat.tuple2Format[AugmentableItem, T]
    new Format[Map[AugmentableItem, T]] {
      def reads(json: JsValue) = json.validate[Seq[(AugmentableItem, T)]].map(_.toMap)
      def writes(itemMap: Map[AugmentableItem, T]) = Json.toJson(itemMap.toSeq)
    }
  }
}

case class AugmentedItem(
  uri: Id[NormalizedURI],
  keep: Option[(Id[Library], Option[Id[User]], Seq[Hashtag])],
  moreKeeps: Seq[(Option[Id[Library]], Option[Id[User]])],
  moreTags: Seq[Hashtag],
  otherPublishedKeeps: Int)

object AugmentedItem {
  def withScores(augmentationScores: AugmentationScores)(item: AugmentableItem, info: AugmentationInfo): AugmentedItem = {
    val keep = item.keptIn.flatMap { libraryId =>
      info.keeps.find(_.keptIn == Some(libraryId)).map { keepInfo =>
        val sortedTags = keepInfo.tags.toSeq.sortBy(augmentationScores.tagScores.getOrElse(_, 0f))
        val userIdOpt = keepInfo.keptBy
        (libraryId, userIdOpt, sortedTags)
      }
    }

    val (allKeeps, allTags) = info.keeps.foldLeft(Set.empty[(Option[Id[Library]], Option[Id[User]])], Set.empty[Hashtag]) {
      case ((moreKeeps, moreTags), RestrictedKeepInfo(_, libraryIdOpt, userIdOpt, tags)) => (moreKeeps + ((libraryIdOpt, userIdOpt)), moreTags ++ tags)
    }

    val (moreKeeps, moreTags) = keep match {
      case Some((libraryId, userIdOpt, tags)) => (allKeeps - ((Some(libraryId), userIdOpt)), allTags -- tags)
      case None => (allKeeps, allTags)
    }

    val moreSortedKeeps = moreKeeps.toSeq.sortBy {
      case (libraryIdOpt, userIdOpt) => (
        -libraryIdOpt.flatMap(augmentationScores.libraryScores.get).getOrElse(0f),
        -userIdOpt.flatMap(augmentationScores.userScores.get).getOrElse(0f)
      )
    }
    val moreSortedTags = moreTags.toSeq.sortBy(-augmentationScores.tagScores.getOrElse(_, 0f))
    AugmentedItem(item.uri, keep, moreSortedKeeps, moreSortedTags, info.otherPublishedKeeps)
  }
}

case class RestrictedKeepInfo(id: ExternalId[Keep], keptIn: Option[Id[Library]], keptBy: Option[Id[User]], tags: Set[Hashtag])

object RestrictedKeepInfo {
  implicit val format = Json.format[RestrictedKeepInfo]
}

case class AugmentationInfo(keeps: Seq[RestrictedKeepInfo], otherPublishedKeeps: Int)
object AugmentationInfo {
  implicit val format = Json.format[AugmentationInfo]
}

case class AugmentationScores(
    libraryScores: Map[Id[Library], Float],
    userScores: Map[Id[User], Float],
    tagScores: Map[Hashtag, Float]) {

  def merge(moreScores: AugmentationScores): AugmentationScores = {
    val addedLibraryScores = (libraryScores.keySet ++ moreScores.libraryScores.keySet).map { id =>
      id -> (libraryScores.getOrElse(id, 0f) + moreScores.libraryScores.getOrElse(id, 0f))
    }.toMap
    val addedUserScores = (userScores.keySet ++ moreScores.userScores.keySet).map { id =>
      id -> (userScores.getOrElse(id, 0f) + moreScores.userScores.getOrElse(id, 0f))
    }.toMap
    val addedTagScores = (tagScores.keySet ++ moreScores.tagScores.keySet).map { id =>
      id -> (tagScores.getOrElse(id, 0f) + moreScores.tagScores.getOrElse(id, 0f))
    }.toMap
    AugmentationScores(addedLibraryScores, addedUserScores, addedTagScores)
  }
}

case class AugmentationContext(userId: Id[User], corpus: Map[AugmentableItem, Float])

object AugmentationContext {
  implicit val format = Json.format[AugmentationContext]
  def uniform(userId: Id[User], items: Seq[AugmentableItem]): AugmentationContext = AugmentationContext(userId, items.map(_ -> 1f).toMap)
}

object AugmentationScores {
  implicit val format = Json.format[AugmentationScores]
  val empty = AugmentationScores(Map.empty, Map.empty, Map.empty)
}

case class ItemAugmentationRequest(items: Set[AugmentableItem], context: AugmentationContext)

object ItemAugmentationRequest {
  implicit val format = Json.format[ItemAugmentationRequest]
}

case class ItemAugmentationResponse(infos: Map[AugmentableItem, AugmentationInfo], scores: AugmentationScores)

object ItemAugmentationResponse {
  implicit val format = Json.format[ItemAugmentationResponse]
  val empty = ItemAugmentationResponse(Map.empty, AugmentationScores.empty)
}
