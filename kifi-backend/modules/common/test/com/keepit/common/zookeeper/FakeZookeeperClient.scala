package com.keepit.common.zookeeper

import com.google.inject.{Inject, Singleton, ImplementedBy}
import java.util.{List => JList}
import com.keepit.common.logging.Logging
import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.collection.immutable.Set
import org.apache.zookeeper.{CreateMode, KeeperException, Watcher, WatchedEvent, ZooKeeper}
import org.apache.zookeeper.data.{ACL, Stat, Id}
import org.apache.zookeeper.ZooDefs.Ids
import org.apache.zookeeper.Watcher.Event.EventType
import org.apache.zookeeper.Watcher.Event.KeeperState
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean

class FakeZooKeeperClient() extends ZooKeeperClient {
  private val db = new mutable.HashMap[Node, Array[Byte]]()
  def registeredCount = db.size
  def nodes = db.keys

  val basePath = Path("")

  def watchNode(node: Node, onDataChanged : Option[Array[Byte]] => Unit) {}
  def watchChildren(path: Path, updateChildren : Seq[Node] => Unit) {}
  def watchChildrenWithData[T](path: Path, watchMap: mutable.Map[Node, T], deserialize: Array[Byte] => T) {}
  def watchChildrenWithData[T](path: Path, watchMap: mutable.Map[Node, T], deserialize: Array[Byte] => T, notifier: Node => Unit) {}

  def create(path: Path, data: Array[Byte], createMode: CreateMode): Path = path
  def createNode(node: Node, data: Array[Byte], createMode: CreateMode): Node = node
  def createPath(path: Path): Path = path

  def getChildren(path: Path): Seq[Node] = Nil
  def get(node: Node): Array[Byte] = db.get(node).getOrElse(Array[Byte](0))

  def set(node: Node, data: Array[Byte]) {
    db(node) = data
  }

  def delete(path: Path) {}
  def deleteNode(node: Node) {}
  def deleteRecursive(path: Path) {}
}
