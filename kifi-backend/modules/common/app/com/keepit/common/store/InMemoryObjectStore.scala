package com.keepit.common.store

import com.keepit.common.logging.Logging
import play.api.Play
import play.api.Play.current
import java.io.{FileOutputStream, File}
import scala.collection.mutable.HashMap
import java.nio.file.Files
import org.apache.commons.io.FileUtils

trait InMemoryObjectStore[A, B]  extends ObjectStore[A, B] with Logging {

  if (Play.isProd) throw new Exception("Can't have in memory object store in production")

  protected val localStore = new HashMap[A, B]()

  def += (kv: (A, B)) = {
    localStore += kv
    val v = if (kv._2 != null) kv._2.toString else ""
    log.debug(s"[+=] (${kv._1} -> ${v.take(50)}) localStore.size=${localStore.size}")
    this
  }

  def -= (key: A) = {
    localStore -= key
    this
  }

  def get(id: A): Option[B] = localStore.get(id)

  override def toString =  s"[size=${localStore.size} keys=${localStore.keySet}"
}

trait InMemoryFileStore[A] extends ObjectStore[A, File] {

  require(!Play.isProd, "Can't have in memory file store in production")

  protected val pathMap = new HashMap[A, String]()
  protected val localStore = FileUtils.getTempDirectory

  def += (kv: (A, File)) = {
    val (key, file) = kv
    val copy = new File(localStore, file.getName)
    copy.deleteOnExit()
    val copyStream = new FileOutputStream(copy)
    Files.copy(file.toPath, copyStream)
    copyStream.close()
    pathMap += (key -> copy.getAbsolutePath)
    this
  }

  def -= (key: A) = {
    get(key).foreach(_.delete())
    pathMap -= key
    this
  }

  def get(key: A): Option[File] = pathMap.get(key).map(new File(_))

  override def toString =  s"[size=${pathMap.size} keys=${pathMap.keySet}"

}
