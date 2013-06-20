package com.keepit.model

import com.keepit.common.db.Id
import com.keepit.common.db.Model
import com.keepit.common.time._
import org.joda.time.DateTime
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
    topic
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
