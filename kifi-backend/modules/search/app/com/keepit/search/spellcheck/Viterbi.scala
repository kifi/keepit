package com.keepit.search.spellcheck

case class Trellis(value: Array[Int])
case class TransitionScore(value: Map[(Int, Int), Float])
case class TransitionScores(value: Array[TransitionScore])
case class Path(path: Array[Int], score: Float)

class Viterbi {
  case class Node(var score: Float = 0.0f, val index: Int, var parent: Option[Node] = None)

  def solve(t: Trellis, s: TransitionScores): Path = {
    solve(t.value, s.value)
  }

  private def traceBack(nodes: Array[Array[Node]]): Path = {
    var path = List.empty[Node]
    val N = nodes.size
    val idx = maxIndex(nodes(N - 1).map(_.score))
    val pathScore = nodes(N - 1)(idx).score
    var node = Option(nodes(N - 1)(idx))
    while (node.isDefined) {
      path = node.get :: path
      node = node.get.parent
    }
    Path(path.map { _.index }.toArray, pathScore)
  }

  private def maxIndex(scores: Array[Float]): Int = {
    var (m, i) = (-Float.MaxValue, -1)
    for (j <- 0 until scores.size) { if (scores(j) > m) { m = scores(j); i = j } }
    i
  }

  private def solve(trellis: Array[Int], scores: Array[TransitionScore]): Path = {
    assume(trellis.size > 1 && trellis.size == scores.size + 1)
    val nodes = trellis.map { layerSize => ((0 until layerSize)).map { i => new Node(index = i) }.toArray }
    nodes(0).foreach { nd => nd.score = 1f }
    for (k <- 0 until (trellis.size - 1)) {
      val scoreMap = scores(k).value
      for (j <- 0 until trellis(k + 1)) {
        var (maxScore, parent) = (-Float.MaxValue, -1)
        for (i <- 0 until trellis(k)) {
          val score = nodes(k)(i).score * scoreMap(i, j)
          if (score > maxScore) { maxScore = score; nodes(k + 1)(j).parent = Some(nodes(k)(i)); nodes(k + 1)(j).score = score }
        }
      }
    }
    traceBack(nodes)
  }
}
