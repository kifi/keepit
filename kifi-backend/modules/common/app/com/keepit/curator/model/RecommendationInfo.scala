package com.keepit.curator.model

import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.model.{ NormalizedURI, User, URISummary }
import com.keepit.social.BasicUser

import com.kifi.macros.json

import org.joda.time.DateTime

@json case class RecoAttributionKind(value: String)

object RecoAttributionKind {
  object Keep extends RecoAttributionKind("keep")
  object Topic extends RecoAttributionKind("topic")
}

@json case class RecoKind(value: String)

object RecoKind {
  object Keep extends RecoKind("keep")
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

@json case class RecoMetaData(
  attribution: Seq[RecoAttributionInfo])

@json case class RecoItemInfo(
  id: ExternalId[NormalizedURI],
  title: Option[String],
  url: String,
  keepers: Seq[BasicUser],
  others: Int,
  siteName: Option[String],
  uriSummary: URISummary)

@json case class FullRecoInfo(
  kind: RecoKind,
  metaData: Option[RecoMetaData],
  itemInfo: RecoItemInfo)

