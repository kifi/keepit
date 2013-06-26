package com.keepit.learning.topicmodel


/**
 * Provides mapping from raw topic array to desired topic array
 * e.g., a raw topic array: ["programming", "investment", "NA", "NA", "programming", "food"]
 * can be mapped to ["programming", "investment", "food"]
 *
 */
trait TopicNameMapper {
  val rawTopicNames: Array[String]              // may contain duplicates or NA
  def idMapper(orginialId: Int): Int
  def nameMapper(originalId: Int): String
}

class IdentityTopicNameMapper(val rawTopicNames: Array[String]) extends TopicNameMapper {
  def idMapper(id: Int) = id
  def nameMapper(originalId: Int) = rawTopicNames(originalId)
}

class ManualTopicNameMapper (val rawTopicNames: Array[String], val mappedNames: Array[String], val mapper: Map[Int, Int]) extends TopicNameMapper {
  def idMapper(id: Int) = mapper.getOrElse(id, -1)
  def nameMapper(originalId: Int) = {
    val newId = idMapper(originalId)
    if (newId < 0 || newId >= mappedNames.size) ""
    else mappedNames(newId)
  }
}

object NameMapperConstructer {
  val naString = "NA"
   // returns unique names and mapper
  def getMapper(rawTopicNames: Array[String]): (Array[String], Map[Int, Int]) = {
    if (rawTopicNames.size == 0) (Array.empty[String], Map.empty[Int, Int])
    else {
      val uniqueNames = rawTopicNames.filter( _.toUpperCase != naString).toSet.toArray
      val nameToIdx = Map.empty[String, Int] ++ uniqueNames.zipWithIndex
      var mapper = Map.empty[Int, Int]
      for(i <- 0 until rawTopicNames.size){
        nameToIdx.get(rawTopicNames(i)) match {
          case Some(idx) => mapper += (i -> idx)
          case None =>  // "NA"
        }
      }
      (uniqueNames, mapper)
    }
  }
}
