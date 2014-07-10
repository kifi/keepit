package com.keepit.search.nlp

import scala.collection.JavaConversions._
import edu.stanford.nlp.trees._;
import edu.stanford.nlp.parser.lexparser.LexicalizedParser
import scala.collection.mutable.ArrayBuffer

object NlpParser {
  val enabled = true
  val parser = LexicalizedParser.loadModel()

  private def getLeastCommonAncestor(root: Tree, nodes: Seq[Tree]): Tree = {
    if (nodes.size == 1) {
      return nodes(0).ancestor(1, root)
    }
    val depths = nodes.map { nd => root.depth(nd) }
    val minDepth = depths.foldLeft(depths(0))((m, x) => m min x)
    val starts = (0 until nodes.size).map { i =>
      nodes(i).ancestor(depths(i) - minDepth, root)
    }
    var ancestors = starts.toSet
    while (ancestors.size != 1) {
      ancestors = ancestors.map { x => x.ancestor(1, root) }
    }
    val rv = ancestors.toArray
    rv(0)
  }

  // return: (start, end), include endpoints.
  private def getSegments(cons: Set[Constituent]): Seq[(Int, Int)] = {
    var segs = new ArrayBuffer[(Int, Int)]
    if (cons.size == 0) {
      return segs
    }
    val pairs = cons.map { x => (x.start(), x.end()) }.toArray
    val sortedPairs = pairs.sortWith((x, y) => (x._1 < y._1) || (x._1 == y._1) && x._2 <= y._2)
    var i = 0
    while (i < sortedPairs.size) {
      val pair = sortedPairs(i)
      segs.append(pair)
      val nextStart = pair._2 + 1
      while (i < sortedPairs.size && sortedPairs(i)._1 < nextStart) i += 1
    }
    segs
  }

  def getTaggedSegments(text: String): Seq[(String, String)] = {
    val tree = parser.parse(text)
    val cons = tree.constituents()
    val leaves = tree.getLeaves[Tree]()
    val segs = getSegments(cons.toSet)
    val tagged = segs.map { seg =>
      val group = leaves.slice(seg._1, seg._2 + 1)
      val phrase = group.map { x => x.label().value().toString }.mkString(" ")
      val nd = getLeastCommonAncestor(tree, group)
      (nd.label().value(), phrase)
    }
    tagged
  }

  def getNonOverlappingConstituents(text: String): Seq[(Int, Int)] = {
    val tree = parser.parse(text)
    val cons = tree.constituents()
    val pairs = cons.map { x => (x.start(), x.end()) }.toSeq
    removeOverlapping(pairs)
  }

  // NOTE: also removes single term constituents
  def removeOverlapping(segments: Seq[(Int, Int)]): Seq[(Int, Int)] = {
    val sorted = segments.filter(x => x._1 < x._2).sortWith((a, b) => (a._2 < b._2) || (a._2 == b._2 && a._1 < b._1)) // sort by right endpoint, then by length of interval
    val rv = new ArrayBuffer[(Int, Int)]
    var pos = -1
    sorted.foreach { seg =>
      if (seg._1 > pos) {
        pos = seg._2
        rv.append(seg)
      }
    }
    rv
  }
}
