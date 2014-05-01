package com.keepit.cortex.models.lda

import com.keepit.cortex.core.StatModel

trait LDA extends StatModel

// mapper: word -> topic vector
case class DenseLDA(dimension: Int, mapper: Map[String, Array[Float]]) extends LDA
