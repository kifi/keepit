package com.keepit.curator.model

import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.model.{ NormalizedURI, User, URISummary, Library }
import com.keepit.social.BasicUser
import com.keepit.common.crypto.PublicId
import com.keepit.commanders.FullLibraryInfo

import com.kifi.macros.json

import play.api.libs.json.{ Json, Writes }

import org.joda.time.DateTime

@json case class RecoAttributionInfo(
  kind: RecoAttributionKind,
  name: Option[String],
  url: Option[String],
  when: Option[DateTime])

@json case class RecoMetaData( //WARNING, adding another field here will break clients, due to the way the @json macro behaves with classes with only one field
  attribution: Seq[RecoAttributionInfo])

@json case class RecoLibraryInfo(owner: BasicUser, id: PublicId[Library], name: String, path: String)

@json case class UriRecoItemInfo(
  id: ExternalId[NormalizedURI],
  title: Option[String],
  url: String,
  keepers: Seq[BasicUser],
  libraries: Seq[RecoLibraryInfo],
  others: Int,
  siteName: Option[String],
  summary: URISummary)

trait FullRecoInfo

case class FullUriRecoInfo(
  kind: RecoKind,
  metaData: Option[RecoMetaData],
  itemInfo: UriRecoItemInfo,
  explain: Option[String] = None) extends FullRecoInfo

object FullUriRecoInfo {
  implicit val writes = Json.writes[FullUriRecoInfo]
}

case class FullLibRecoInfo(
  kind: RecoKind,
  metaData: Option[RecoMetaData],
  itemInfo: FullLibraryInfo,
  explain: Option[String] = None) extends FullRecoInfo

object FullLibRecoInfo {
  implicit val writes = Json.writes[FullLibRecoInfo]
}

object FullRecoInfo {
  implicit val writes = new Writes[FullRecoInfo] {
    def writes(obj: FullRecoInfo) = obj match {
      case uri: FullUriRecoInfo => Json.toJson(uri)
      case lib: FullLibRecoInfo => Json.toJson(lib)
    }
  }
}
