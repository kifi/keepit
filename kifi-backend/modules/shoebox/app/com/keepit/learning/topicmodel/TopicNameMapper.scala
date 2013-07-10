package com.keepit.learning.topicmodel


/**
 * Provides mapping from raw topic array to desired topic array
 * e.g., a raw topic array: ["programming", "investment", "NA", "NA", "programming", "food"]
 * can be mapped to ["programming", "investment", "food"]
 *
 */
trait TopicNameMapper {
  val rawTopicNames: Array[String]              // may contain duplicates or NA
  val mappedNames: Array[String]
  def idMapper(orginialId: Int): Int
  def getMappedNameByOriginalId(originalId: Int): String
  def getMappedNameByNewId(newId: Int): String
  def scoreMapper(score: Array[Int]): Array[Int]
  def scoreMapper(score: Array[Double]): Array[Double]
}

class IdentityTopicNameMapper(val rawTopicNames: Array[String]) extends TopicNameMapper {
  val mappedNames = rawTopicNames
  def idMapper(id: Int) = id
  def getMappedNameByOriginalId(originalId: Int) = rawTopicNames(originalId)
  def getMappedNameByNewId(newId: Int) = mappedNames(newId)
  def scoreMapper(score: Array[Int]) = score
  def scoreMapper(score: Array[Double]) = score
}

class ManualTopicNameMapper (val rawTopicNames: Array[String], val mappedNames: Array[String], val mapper: Map[Int, Int]) extends TopicNameMapper {
  def idMapper(id: Int) = mapper.getOrElse(id, -1)
  def getMappedNameByOriginalId(originalId: Int) = {
    val newId = idMapper(originalId)
    if (newId < 0 || newId >= mappedNames.size) ""
    else mappedNames(newId)
  }

  def getMappedNameByNewId(newId: Int) = mappedNames(newId)

  def scoreMapper(score: Array[Int]) = {
    val rv = new Array[Int](mappedNames.size)
    (0 until score.length).foreach { i =>
      val idx = idMapper(i)
      if (idx != -1) rv(idx) += score(i)
    }
    rv
  }

  def scoreMapper(score: Array[Double]) = {
    val rv = new Array[Double](mappedNames.size)
    (0 until score.length).foreach { i =>
      val idx = idMapper(i)
      if (idx != -1) rv(idx) += score(i)
    }
    rv
  }
}

object NameMapperConstructer {
  val naString = TopicModelGlobal.naString
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
