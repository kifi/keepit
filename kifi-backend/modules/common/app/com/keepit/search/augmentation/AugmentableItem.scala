package com.keepit.search.augmentation

import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.model._
import org.joda.time.DateTime
import play.api.libs.json._
import com.keepit.common.json.TupleFormat

case class AugmentableItem(uri: Id[NormalizedURI], keepId: Option[Id[Keep]] = None)

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

case class RestrictedKeepInfo(id: Id[Keep], externalId: ExternalId[Keep], keptAt: DateTime, keptIn: Option[Id[Library]], organizationId: Option[Id[Organization]], keptBy: Option[Id[User]], note: Option[String], tags: Set[Hashtag])

object RestrictedKeepInfo {
  implicit val format = Json.format[RestrictedKeepInfo]
}

case class FullAugmentationInfo(keeps: Seq[RestrictedKeepInfo], otherPublishedKeeps: Int, otherDiscoverableKeeps: Int, librariesTotal: Int, keepersTotal: Int)
object FullAugmentationInfo {
  implicit val format = Json.format[FullAugmentationInfo]
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

  def byUser(userId: Id[User]): Float = userScores.getOrElse(userId, 0f)
  def byLibrary(libraryId: Id[Library]): Float = libraryScores.getOrElse(libraryId, 0f)
  def byTag(tag: Hashtag): Float = tagScores.getOrElse(tag, 0f)
}

case class AugmentationContext(userId: Id[User], corpus: Map[AugmentableItem, Float])

object AugmentationContext {
  implicit val format = Json.format[AugmentationContext]
  def uniform(userId: Id[User], items: Set[AugmentableItem]): AugmentationContext = AugmentationContext(userId, items.map(_ -> 1f).toMap)
}

object AugmentationScores {
  implicit val format = Json.format[AugmentationScores]
  val empty = AugmentationScores(Map.empty, Map.empty, Map.empty)
}

case class ItemAugmentationRequest(items: Set[AugmentableItem], context: AugmentationContext, showPublishedLibraries: Option[Boolean] = None)

object ItemAugmentationRequest {
  implicit val writes = Json.format[ItemAugmentationRequest]
  def uniform(userId: Id[User], items: AugmentableItem*): ItemAugmentationRequest = {
    val itemSet = items.toSet
    val context = AugmentationContext.uniform(userId, itemSet)
    ItemAugmentationRequest(itemSet, context)
  }
}

case class ItemAugmentationResponse(infos: Map[AugmentableItem, FullAugmentationInfo], scores: AugmentationScores)

object ItemAugmentationResponse {
  implicit val format = Json.format[ItemAugmentationResponse]
  val empty = ItemAugmentationResponse(Map.empty, AugmentationScores.empty)
}

// todo(Léo): reconsider keeps, include sources
case class LimitedAugmentationInfo(
  keep: Option[RestrictedKeepInfo],
  keeps: Seq[RestrictedKeepInfo],
  keepsOmitted: Int,
  keepers: Seq[(Id[User], DateTime)],
  keepersOmitted: Int,
  keepersTotal: Int,
  libraries: Seq[(Id[Library], Id[User], DateTime)],
  librariesOmitted: Int,
  librariesTotal: Int,
  tags: Seq[Hashtag],
  tagsOmitted: Int)

object LimitedAugmentationInfo {
  implicit val format = {
    implicit val keeperFormat: Format[(Id[User], DateTime)] = TupleFormat.tuple2Format[Id[User], DateTime]
    implicit val libraryFormat: Format[(Id[Library], Id[User], DateTime)] = TupleFormat.tuple3Format[Id[Library], Id[User], DateTime]
    Json.format[LimitedAugmentationInfo]
  }

  val empty = LimitedAugmentationInfo(None, Seq.empty, 0, Seq.empty, 0, 0, Seq.empty, 0, 0, Seq.empty, 0)
}

case class SharingUserInfo(
  sharingUserIds: Set[Id[User]],
  keepersEdgeSetSize: Int)

object SharingUserInfo {
  private implicit val userIdFormat = Id.format[User]
  implicit val sharingUserInfoFormat = Json.format[SharingUserInfo]
}