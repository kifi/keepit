package com.keepit.curator.model

import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.model.{ NormalizedURI, User, URISummary, Library }
import com.keepit.social.BasicUser
import com.keepit.common.crypto.PublicId

import com.kifi.macros.json

import play.api.libs.json.{ Json, Writes }

import org.joda.time.DateTime

@json case class RecoAttributionKind(value: String)

object RecoAttributionKind {
  object Keep extends RecoAttributionKind("keep")
  object Topic extends RecoAttributionKind("topic")
  object Library extends RecoAttributionKind("library")
}

@json case class RecoKind(value: String)

object RecoKind {
  object Keep extends RecoKind("keep")
  object Library extends RecoKind("library")
}

@json case class RecoInfo(
  userId: Option[Id[User]], //who is this recommendation for
  uriId: Id[NormalizedURI], //what uri is being recommended
  score: Float, //the score of the uri
  explain: Option[String], //some explanation of the score, *not* meant to be seen by the user
  attribution: Option[SeedAttribution])

@json case class RecoAttributionInfo(
  kind: RecoAttributionKind,
  name: Option[String],
  url: Option[String],
  when: Option[DateTime])

@json case class RecoMetaData( //WARNING, adding another field here will break clients, due to the way the @json macro behaves with classes with only one field
  attribution: Seq[RecoAttributionInfo])

@json case class RecoLibraryInfo(owner: BasicUser, id: PublicId[Library], name: String, path: String)

trait RecoItemInfo

@json case class UriRecoItemInfo(
  id: ExternalId[NormalizedURI],
  title: Option[String],
  url: String,
  keepers: Seq[BasicUser],
  libraries: Seq[RecoLibraryInfo],
  others: Int,
  siteName: Option[String],
  summary: URISummary) extends RecoItemInfo

@json case class LibRecoItemInfo(
  id: PublicId[Library],
  name: String,
  url: String,
  description: Option[String],
  owner: BasicUser,
  followers: Seq[BasicUser],
  numFollowers: Int,
  numKeeps: Int) extends RecoItemInfo

object RecoItemInfo {
  implicit val writes = new Writes[RecoItemInfo] {
    def writes(obj: RecoItemInfo) = obj match {
      case uri: UriRecoItemInfo => Json.toJson(uri)
      case lib: LibRecoItemInfo => Json.toJson(lib)
    }
  }
}

case class FullRecoInfo(
  kind: RecoKind,
  metaData: Option[RecoMetaData],
  itemInfo: RecoItemInfo,
  explain: Option[String] = None)

object FullRecoInfo {
  implicit val writes = Json.writes[FullRecoInfo]
}

