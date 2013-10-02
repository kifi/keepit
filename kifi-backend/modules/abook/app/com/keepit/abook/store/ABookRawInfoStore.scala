package com.keepit.abook.store


import com.keepit.common.db.{State, States, Id}
import com.keepit.common.store._
import com.keepit.model.{ABookOriginType, ABook, ContactInfo, User}
import com.amazonaws.services.s3._
import play.api.libs.json.{JsArray, JsValue, Format}

case class ABookRawInfo(userId:Id[User], origin:ABookOriginType, contacts:JsArray)

object ABookRawInfo {
  import play.api.libs.functional.syntax._
  import play.api.libs.json._
  import com.keepit.common.db.Id

  implicit val format = (
    (__ \ 'userId).format(Id.format[User]) and
    (__ \ 'origin).format[String].inmap(ABookOriginType.apply _, unlift(ABookOriginType.unapply)) and
    (__ \ 'contacts).format[JsArray]
  )(ABookRawInfo.apply _, unlift(ABookRawInfo.unapply))

}

trait ABookRawInfoStore extends ObjectStore[String, ABookRawInfo]

class S3ABookRawInfoStoreImpl(val bucketName: S3Bucket, val amazonS3Client: AmazonS3,
                              val formatter: Format[ABookRawInfo] = ABookRawInfo.format)
  extends S3JsonStore[String, ABookRawInfo] with ABookRawInfoStore

class InMemoryABookRawInfoStoreImpl extends InMemoryObjectStore[String, ABookRawInfo] with ABookRawInfoStore
