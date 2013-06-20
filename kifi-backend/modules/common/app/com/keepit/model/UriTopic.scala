package com.keepit.model

import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.db.Id
import com.keepit.common.db.Model
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.keepit.search.topicModel.TopicModelGlobal
import java.io.{DataOutputStream, DataInputStream, ByteArrayInputStream, ByteArrayOutputStream}

case class UriTopic(
  id: Option[Id[UriTopic]] = None,
  uriId: Id[NormalizedURI],
  topic: Array[Byte],           // hold array of doubles, topic membership
  primaryTopic: Option[Int] = None,
  secondaryTopic: Option[Int] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime
) extends Model[UriTopic] {
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withId(id: Id[UriTopic]) = this.copy(id = Some(id))
}

class UriTopicHelper {
  def toByteArray(arr: Array[Double]) = {
    assume(arr.length == TopicModelGlobal.numTopics, "topic array size not matching TopicModelGlobal.numTopics")
    val bs = new ByteArrayOutputStream(TopicModelGlobal.numTopics * 8)
    val os = new DataOutputStream(bs)
    arr.foreach{os.writeDouble}
    os.close()
    val rv = bs.toByteArray()
    bs.close()
    rv
  }

  def toDoubleArray(arr: Array[Byte]) = {
    assume(arr.size == TopicModelGlobal.numTopics * 8, s"topic array size ${arr.size} not matching TopicModelGlobal.numTopics")
    val is = new DataInputStream(new ByteArrayInputStream(arr))
    val topic = (0 until TopicModelGlobal.numTopics).map{i => is.readDouble()}
    is.close()
    topic.toArray
  }

  def assignTopics(arr: Array[Double]): (Option[Int], Option[Int]) = {
    assume(arr.length > 2 && arr.length == TopicModelGlobal.numTopics, s"topic array size ${arr.size} less than 3 or not matching TopicModelGlobal.numTopics")
    var i = arr.indexWhere(x => x!=arr(0), 1)
    if (i == -1) (None, None)
    else {
      var (smallIdx, bigIdx) = if (arr(i) > arr(0)) (0, i) else (i, 0)
      i += 1
      while(i < arr.size){
        if( arr(i) > arr(bigIdx) ) {
          smallIdx = bigIdx; bigIdx = i
        } else if (arr(i) > arr(smallIdx)){
          smallIdx = i
        }
        i += 1
      }
      if (arr(bigIdx) < TopicModelGlobal.primaryTopicThreshold) (None, None)
      else {
        if (arr(bigIdx) * TopicModelGlobal.topicTailcut < arr(smallIdx)) (Some(bigIdx), Some(smallIdx))
        else (Some(bigIdx), None)
      }
    }
  }
}

@ImplementedBy(classOf[UriTopicRepoImpl])
trait UriTopicRepo extends Repo[UriTopic]{
  def getByUriId(uriId: Id[NormalizedURI])(implicit session: RSession):Option[UriTopic]
  def getAssignedTopicsByUriId(uriId: Id[NormalizedURI])(implicit session: RSession): Option[(Option[Int], Option[Int])]
}

@Singleton
class UriTopicRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock
) extends DbRepo[UriTopic] with UriTopicRepo {
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._

  override val table = new RepoTable[UriTopic](db, "uri_topic"){
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def topic = column[Array[Byte]]("topic", O.NotNull)
    def primaryTopic = column[Option[Int]]("primary_topic")
    def secondaryTopic = column[Option[Int]]("secondary_topic")
    def * = id.? ~ uriId ~ topic ~ primaryTopic ~ secondaryTopic ~ createdAt ~ updatedAt <> (UriTopic, UriTopic.unapply _)
  }

  def getByUriId(uriId: Id[NormalizedURI])(implicit session: RSession): Option[UriTopic] = {
    (for(r <- table if r.uriId === uriId) yield r).firstOption
  }

  def getAssignedTopicsByUriId(uriId: Id[NormalizedURI])(implicit session: RSession): Option[(Option[Int], Option[Int])] = {
    (for(r <- table if r.uriId === uriId) yield (r.primaryTopic, r.secondaryTopic)).firstOption
  }

}
