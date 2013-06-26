package com.keepit.learning.topicmodel

import org.specs2.mutable.Specification

class TopicNameMapperTest extends Specification {
  "TopicNameMapper" should {
    "correctly construct idMapper" in {
      val names = Array("math", "cs", "cs", "na", "NA", "math", "web")
      val (newNames, mapper) = NameMapperConstructer.getMapper(names)
      newNames.toSet == Set("math", "cs", "web")
      mapper === Map(0 -> 0, 1 -> 1, 2 -> 1, 5 -> 0, 6 -> 2)
    }

    "manual topic mapper should work" in {
      val names = Array("math", "cs", "cs", "na", "NA", "math", "web")
      val (newNames, mapper) = NameMapperConstructer.getMapper(names)
      val topicMapper = new ManualTopicNameMapper(names, newNames, mapper)
      (0 until names.size).map{topicMapper.idMapper(_)}.toArray === Array(0, 1, 1, -1, -1, 0, 2)
      (0 until names.size).map{topicMapper.nameMapper(_)}.toArray === Array("math", "cs", "cs", "", "", "math", "web")
    }
  }
}
