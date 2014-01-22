package com.keepit.graph.model

import play.api.libs.json.Format

trait Companion[T] {
  def typeCode: TypeCode[T]
  def format: Format[T]
  implicit def companion: Companion[T] = this
  implicit def instanceToCompanion(instance: T): Companion[T] = this
}

trait TypeCode[+T]

object Companion {
  val fromTypeCodeString = Map[String, Companion[_]](
    UserData.typeCode.toString() -> UserData,
    CollectionData.typeCode.toString() -> CollectionData,
    UriData.typeCode.toString() -> UriData,
    CollectsData.typeCode.toString() -> CollectsData,
    ContainsData.typeCode.toString() -> ContainsData,
    FollowsData.typeCode.toString() -> FollowsData,
    KeptData.typeCode.toString() -> KeptData
  )
}
